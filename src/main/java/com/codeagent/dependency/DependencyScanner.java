package com.codeagent.dependency;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 저장소의 빌드 파일(pom.xml, build.gradle(.kts), gradle/libs.versions.toml, gradle.lockfile)에서 라이브러리와 버전을 읽는다.
 * 빌드 도구를 실행하지 않고 파일만 읽으므로, 전이 의존성은 gradle.lockfile 이 있을 때만 알 수 있다.
 * 버전이 적혀 있지 않은 Spring Boot 스타터는 Spring Boot 플러그인·부모 POM 의 버전으로 채운다.
 */
public final class DependencyScanner {

    /** 찾은 라이브러리, 읽은 빌드 파일, 버전을 알 수 없는 라이브러리 */
    public record ScanResult(List<Dependency> dependencies, List<String> files, List<String> unversioned) {
    }

    private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", ".idea", "build", "target", "out", "node_modules", "bin");
    private static final String BOOT_GROUP = "org.springframework.boot";
    private static final int MAX_DEPTH = 8;
    private static final long MAX_FILE_BYTES = 2_000_000;

    private static final Pattern GRADLE_COORDINATE = Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+):([^'\"@:\\s]+)(?::[\\w\\-]+)?(?:@\\w+)?['\"]");
    private static final Pattern GRADLE_MAP = Pattern.compile(
            "group\\s*[:=]\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*name\\s*[:=]\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*version\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_UNVERSIONED = Pattern.compile(
            "\\b(\\w*(?:[Ii]mplementation|[Aa]pi|[Oo]nly|[Pp]rocessor))\\s*\\(?\\s*['\"]([\\w.\\-]+):([\\w.\\-]+)['\"]");
    private static final Pattern GRADLE_BOOT_PLUGIN = Pattern.compile(
            "id\\s*\\(?\\s*['\"]org\\.springframework\\.boot['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_VARIABLE = Pattern.compile(
            "(?m)^\\s*(?:def\\s+|val\\s+|var\\s+|ext\\.|extra\\[\")?([A-Za-z_][\\w.]*)\"?\\]?\\s*=\\s*['\"]([^'\"$]+)['\"]");
    private static final Pattern GRADLE_EXT_SET = Pattern.compile("set\\(\\s*['\"]([\\w.]+)['\"]\\s*,\\s*['\"]([^'\"$]+)['\"]\\s*\\)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{?([\\w.]+)}?");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([\\w.\\-]+)]\\s*$");
    private static final Pattern TOML_ENTRY = Pattern.compile("^\\s*([\\w.\\-]+)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern LOCKFILE_ENTRY = Pattern.compile("^([\\w.\\-]+):([\\w.\\-]+):([^=\\s]+)=(.*)$");

    private DependencyScanner() {
    }

    public static ScanResult scan(Path root) {
        Collector collector = new Collector(root);
        List<Path> buildFiles = findBuildFiles(root);
        // 변수·Spring Boot 버전을 먼저 모은 뒤 라이브러리를 읽는다
        for (Path file : buildFiles) {
            String name = file.getFileName().toString();
            if (name.equals("gradle.properties")) {
                collector.readGradleProperties(file);
            } else if (name.startsWith("build.gradle") || name.startsWith("settings.gradle")) {
                collector.readGradleVariables(file);
            }
        }
        for (Path file : buildFiles) {
            String name = file.getFileName().toString();
            switch (name) {
                case "pom.xml" -> collector.readPom(file);
                case "libs.versions.toml" -> collector.readVersionCatalog(file);
                case "gradle.lockfile" -> collector.readLockfile(file);
                default -> {
                    if (name.startsWith("build.gradle")) {
                        collector.readGradle(file);
                    }
                }
            }
        }
        return collector.result(buildFiles);
    }

    private static List<Path> findBuildFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> isBuildFile(p.getFileName().toString()))
                    .filter(p -> !skipped(root.relativize(p)))
                    .filter(p -> p.toFile().length() <= MAX_FILE_BYTES)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("빌드 파일을 찾지 못함: " + e.getMessage(), e);
        }
    }

    private static boolean isBuildFile(String name) {
        return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("gradle.properties") || name.equals("libs.versions.toml") || name.equals("gradle.lockfile");
    }

    /** 빌드 결과물 폴더와 테스트 자료(src/test)의 빌드 파일은 이 프로젝트의 의존성이 아니다 */
    private static boolean skipped(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String part = relative.getName(i).toString();
            if (SKIP_DIRS.contains(part) || (part.equals("src") && i + 1 < relative.getNameCount() - 1
                    && relative.getName(i + 1).toString().equals("test"))) {
                return true;
            }
        }
        return false;
    }

    private static final class Collector {

        private final Path root;
        private final Map<String, String> variables = new HashMap<>();
        private final Map<String, Dependency> found = new LinkedHashMap<>();
        private final Map<String, String> unversioned = new LinkedHashMap<>();
        private String bootVersion;

        private Collector(Path root) {
            this.root = root;
        }

        ScanResult result(List<Path> files) {
            // 나중에 버전을 알게 된 라이브러리는 버전 없는 목록에서 뺀다
            found.values().forEach(d -> unversioned.remove(d.coordinate()));
            if (bootVersion != null) {
                for (String coordinate : List.copyOf(unversioned.keySet())) {
                    if (coordinate.startsWith(BOOT_GROUP + ":")) {
                        unversioned.remove(coordinate);
                    }
                }
            }
            return new ScanResult(List.copyOf(found.values()),
                    files.stream().map(this::relative).filter(f -> !f.endsWith("gradle.properties") && !f.contains("settings.gradle")).toList(),
                    unversioned.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList());
        }

        // ---- Gradle ----

        void readGradleProperties(Path file) {
            for (String line : lines(file)) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.trim().startsWith("#")) {
                    variables.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        void readGradleVariables(Path file) {
            String text = read(file);
            Matcher variable = GRADLE_VARIABLE.matcher(text);
            while (variable.find()) {
                variables.putIfAbsent(variable.group(1), variable.group(2));
            }
            Matcher set = GRADLE_EXT_SET.matcher(text);
            while (set.find()) {
                variables.putIfAbsent(set.group(1), set.group(2));
            }
            Matcher boot = GRADLE_BOOT_PLUGIN.matcher(text);
            if (boot.find() && bootVersion == null) {
                bootVersion = resolve(boot.group(1));
                if (bootVersion != null) {
                    add(BOOT_GROUP, "spring-boot", bootVersion, file, lineOf(text, boot.start()), "plugin");
                }
            }
        }

        void readGradle(Path file) {
            String text = read(file);
            Matcher coordinate = GRADLE_COORDINATE.matcher(text);
            while (coordinate.find()) {
                String group = coordinate.group(1);
                String artifact = coordinate.group(2);
                if (artifact.equals("spring-boot-gradle-plugin") && bootVersion == null) {
                    bootVersion = resolve(coordinate.group(3));
                }
                addOrUnversioned(group, artifact, coordinate.group(3), file, lineOf(text, coordinate.start()), scopeAt(text, coordinate.start()));
            }
            Matcher map = GRADLE_MAP.matcher(text);
            while (map.find()) {
                addOrUnversioned(map.group(1), map.group(2), map.group(3), file, lineOf(text, map.start()), scopeAt(text, map.start()));
            }
            Matcher bare = GRADLE_UNVERSIONED.matcher(text);
            while (bare.find()) {
                String scope = bare.group(1).toLowerCase().startsWith("test") ? "test" : "dependency";
                addOrUnversioned(bare.group(2), bare.group(3), null, file, lineOf(text, bare.start()), scope);
            }
        }

        private static String scopeAt(String text, int offset) {
            int lineStart = text.lastIndexOf('\n', offset) + 1;
            String prefix = text.substring(lineStart, offset).trim().toLowerCase();
            if (prefix.startsWith("test")) {
                return "test";
            }
            return prefix.startsWith("classpath") ? "plugin" : "dependency";
        }

        // ---- 버전 카탈로그 (gradle/libs.versions.toml) ----

        void readVersionCatalog(Path file) {
            List<String> lines = lines(file);
            Map<String, String> versions = new HashMap<>();
            String section = "";
            for (String line : lines) {
                Matcher header = TOML_SECTION.matcher(line);
                if (header.matches()) {
                    section = header.group(1);
                    continue;
                }
                Matcher entry = TOML_ENTRY.matcher(line);
                if (section.equals("versions") && entry.matches()) {
                    String value = unquote(entry.group(2));
                    if (value != null) {
                        versions.put(entry.group(1), value);
                    }
                }
            }
            section = "";
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher header = TOML_SECTION.matcher(line);
                if (header.matches()) {
                    section = header.group(1);
                    continue;
                }
                Matcher entry = TOML_ENTRY.matcher(line);
                if (!entry.matches()) {
                    continue;
                }
                String value = entry.group(2);
                if (section.equals("libraries")) {
                    readCatalogLibrary(value, versions, file, i + 1);
                } else if (section.equals("plugins") && value.contains("org.springframework.boot")) {
                    String version = catalogVersion(value, versions);
                    if (version != null && bootVersion == null) {
                        bootVersion = version;
                        add(BOOT_GROUP, "spring-boot", version, file, i + 1, "plugin");
                    }
                }
            }
        }

        private void readCatalogLibrary(String value, Map<String, String> versions, Path file, int line) {
            String plain = unquote(value);
            if (plain != null) {
                String[] parts = plain.split(":");
                if (parts.length >= 2) {
                    addOrUnversioned(parts[0], parts[1], parts.length >= 3 ? parts[2] : null, file, line, "dependency");
                }
                return;
            }
            String module = inlineField(value, "module");
            String group = inlineField(value, "group");
            String name = inlineField(value, "name");
            if (module != null && module.contains(":")) {
                group = module.substring(0, module.indexOf(':'));
                name = module.substring(module.indexOf(':') + 1);
            }
            if (group != null && name != null) {
                addOrUnversioned(group, name, catalogVersion(value, versions), file, line, "dependency");
            }
        }

        private static String catalogVersion(String value, Map<String, String> versions) {
            String ref = inlineField(value, "version.ref");
            if (ref != null) {
                return versions.get(ref);
            }
            return inlineField(value, "version");
        }

        private static String inlineField(String inlineTable, String key) {
            Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\"").matcher(inlineTable);
            return m.find() ? m.group(1) : null;
        }

        private static String unquote(String value) {
            String v = value.trim();
            return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : null;
        }

        // ---- gradle.lockfile (전이 의존성까지 고정된 버전) ----

        void readLockfile(Path file) {
            List<String> lines = lines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LOCKFILE_ENTRY.matcher(lines.get(i).trim());
                if (m.matches() && !m.group(4).isBlank()) {
                    boolean testOnly = Stream.of(m.group(4).split(",")).allMatch(c -> c.trim().toLowerCase().startsWith("test"));
                    add(m.group(1), m.group(2), m.group(3), file, i + 1, testOnly ? "test" : "lockfile");
                }
            }
        }

        // ---- Maven ----

        void readPom(Path file) {
            Document document = parseXml(file);
            if (document == null) {
                return;
            }
            Element project = document.getDocumentElement();
            String text = read(file);
            Map<String, String> properties = new HashMap<>(variables);
            Element props = child(project, "properties");
            if (props != null) {
                NodeList children = props.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element e) {
                        properties.put(e.getTagName(), e.getTextContent().trim());
                    }
                }
            }
            Element parent = child(project, "parent");
            String parentVersion = parent == null ? null : text(parent, "version");
            String projectVersion = text(project, "version") != null ? text(project, "version") : parentVersion;
            if (projectVersion != null) {
                properties.put("project.version", projectVersion);
            }
            if (parentVersion != null) {
                properties.put("project.parent.version", parentVersion);
            }
            if (parent != null && BOOT_GROUP.equals(text(parent, "groupId")) && parentVersion != null) {
                bootVersion = bootVersion == null ? parentVersion : bootVersion;
                add(BOOT_GROUP, "spring-boot", parentVersion, file, lineOf(text, text.indexOf("<parent>")), "parent");
            }
            readPomDependencies(child(project, "dependencies"), properties, file, text);
            Element management = child(project, "dependencyManagement");
            if (management != null) {
                readPomDependencies(child(management, "dependencies"), properties, file, text);
            }
        }

        private void readPomDependencies(Element dependencies, Map<String, String> properties, Path file, String text) {
            if (dependencies == null) {
                return;
            }
            NodeList children = dependencies.getElementsByTagName("dependency");
            int searchFrom = 0;
            for (int i = 0; i < children.getLength(); i++) {
                Element dependency = (Element) children.item(i);
                String group = text(dependency, "groupId");
                String artifact = text(dependency, "artifactId");
                if (group == null || artifact == null) {
                    continue;
                }
                String version = text(dependency, "version");
                if (version != null) {
                    version = replacePlaceholders(version, properties);
                }
                int at = text.indexOf("<artifactId>" + artifact + "</artifactId>", searchFrom);
                if (at >= 0) {
                    searchFrom = at + 1;
                }
                String scope = "test".equals(text(dependency, "scope")) ? "test" : "dependency";
                addOrUnversioned(replacePlaceholders(group, properties), artifact, version, file, at < 0 ? 0 : lineOf(text, at), scope);
            }
        }

        private static Document parseXml(Path file) {
            try {
                // 저장소의 XML 은 믿을 수 없으므로 외부 엔티티를 모두 막는다 (XXE 방지)
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                return factory.newDocumentBuilder().parse(file.toFile());
            } catch (Exception e) {
                return null;
            }
        }

        private static Element child(Element parent, String tag) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element e && e.getTagName().equals(tag)) {
                    return e;
                }
            }
            return null;
        }

        private static String text(Element parent, String tag) {
            Element e = child(parent, tag);
            return e == null ? null : e.getTextContent().trim();
        }

        // ---- 공통 ----

        private void addOrUnversioned(String group, String artifact, String version, Path file, int line, String scope) {
            String resolved = version == null ? null : resolve(version);
            if (resolved == null && group.equals(BOOT_GROUP) && bootVersion != null) {
                resolved = bootVersion;
            }
            if (resolved == null) {
                unversioned.putIfAbsent(group + ":" + artifact, relative(file) + (line > 0 ? ":" + line : ""));
                return;
            }
            add(group, artifact, resolved, file, line, scope);
        }

        private void add(String group, String artifact, String version, Path file, int line, String scope) {
            found.putIfAbsent(group + ":" + artifact + ":" + version, new Dependency(group, artifact, version, relative(file), line, scope));
        }

        /** ${var} 를 채운다. 못 채우거나 범위·동적 버전(1.+, [1,2))이면 null */
        private String resolve(String version) {
            String value = replacePlaceholders(version, variables);
            if (value == null || value.contains("$") || value.contains("+") || value.contains("[") || value.contains("(")
                    || value.isBlank() || value.equalsIgnoreCase("latest.release")) {
                return null;
            }
            return value;
        }

        private static String replacePlaceholders(String value, Map<String, String> properties) {
            Matcher m = PLACEHOLDER.matcher(value);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String replacement = properties.get(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : m.group()));
            }
            m.appendTail(out);
            return out.toString();
        }

        private String relative(Path file) {
            return root.relativize(file).toString().replace('\\', '/');
        }

        private static int lineOf(String text, int offset) {
            if (offset < 0) {
                return 0;
            }
            int line = 1;
            for (int i = 0; i < offset && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        private static String read(Path file) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        private static List<String> lines(Path file) {
            return new ArrayList<>(List.of(read(file).split("\\R", -1)));
        }
    }
}
