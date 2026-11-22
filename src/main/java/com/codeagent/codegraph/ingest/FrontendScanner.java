package com.codeagent.codegraph.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 프론트엔드 코드(JS/TS/JSX/TSX/Vue/Svelte/HTML)에서 위험한 패턴을 찾는다. 파서 없이 줄 단위 정규식으로 찾으므로
 * 후보일 뿐이고, 값이 사용자에게서 오는지·정리(sanitize)되는지는 Worker 가 코드를 읽고 판단한다.
 * 프론트엔드에는 HTTP 진입점이 없어서 진입점 → 위험 지점 경로로 이어지지 않는다.
 */
public final class FrontendScanner {

    /** 찾은 위험 지점. snippet 의 비밀값은 가려져 있다 */
    public record FrontendSink(String filePath, int line, int column, String category, String cwe, String api, String snippet) {
    }

    private record Rule(String category, String cwe, String api, Pattern pattern, boolean secret) {
    }

    private static final Set<String> EXTENSIONS = Set.of("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "html", "htm");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "bower_components", "dist", "build", "out",
            "target", "coverage", ".next", ".nuxt", ".output", ".svelte-kit", "vendor", "e2e", "__tests__", "__mocks__", ".gradle", ".idea");
    private static final int MAX_DEPTH = 15;
    private static final long MAX_FILE_BYTES = 1_000_000;
    /** 이보다 긴 줄은 압축(minify)된 코드로 보고 건너뛴다 */
    private static final int MAX_LINE_LENGTH = 1_000;
    private static final int MAX_SINKS = 2_000;
    private static final int SNIPPET_MAX_LENGTH = 160;

    private static final String NOT_LITERAL = "(?!\\s*['\"`]|\\s*$)";

    private static final List<Rule> RULES = List.of(
            // XSS: 사용자 값이 HTML 로 해석되는 곳
            rule("XSS", "CWE-79", "React dangerouslySetInnerHTML", "dangerouslySetInnerHTML"),
            rule("XSS", "CWE-79", "element.innerHTML / outerHTML", "\\.(?:inner|outer)HTML\\s*\\+?=(?!=)"),
            rule("XSS", "CWE-79", "element.insertAdjacentHTML", "\\.insertAdjacentHTML\\s*\\("),
            rule("XSS", "CWE-79", "document.write", "\\bdocument\\.write(?:ln)?\\s*\\("),
            rule("XSS", "CWE-79", "Vue v-html", "\\bv-html\\s*="),
            rule("XSS", "CWE-79", "Angular [innerHTML]", "\\[innerHTML\\]\\s*="),
            rule("XSS", "CWE-79", "Angular bypassSecurityTrust*", "\\bbypassSecurityTrust(?:Html|Script|Style|Url|ResourceUrl)\\s*\\("),
            rule("XSS", "CWE-79", "Svelte {@html}", "\\{@html\\s"),
            rule("XSS", "CWE-79", "jQuery .html()", "\\.html\\(\\s*[^)\\s]"),
            // 코드 실행
            rule("CODE_INJECTION", "CWE-95", "eval", "(?<![\\w.])eval\\s*\\("),
            rule("CODE_INJECTION", "CWE-95", "new Function", "\\bnew\\s+Function\\s*\\("),
            rule("CODE_INJECTION", "CWE-95", "setTimeout/setInterval (문자열)", "\\bset(?:Timeout|Interval)\\s*\\(\\s*['\"`]"),
            // 오픈 리다이렉트: 상수가 아닌 값으로 이동
            rule("OPEN_REDIRECT", "CWE-601", "location 이동", "\\blocation(?:\\.href)?\\s*=(?!=)" + NOT_LITERAL),
            rule("OPEN_REDIRECT", "CWE-601", "location.assign / replace", "\\blocation\\.(?:assign|replace)\\s*\\(" + NOT_LITERAL),
            rule("OPEN_REDIRECT", "CWE-601", "window.open", "\\bwindow\\.open\\s*\\(" + NOT_LITERAL),
            // 로그인 토큰을 스크립트가 읽을 수 있는 저장소에 둠 (XSS 한 번이면 털린다)
            ruleIgnoreCase("TOKEN_STORAGE", "CWE-922", "localStorage/sessionStorage 토큰 저장",
                    "\\b(?:local|session)Storage\\.setItem\\s*\\(\\s*['\"`]?[\\w.-]*(?:token|jwt|auth|session|password|secret|credential)",
                    false),
            ruleIgnoreCase("TOKEN_STORAGE", "CWE-922", "localStorage/sessionStorage 토큰 저장",
                    "\\b(?:local|session)Storage\\.(?:\\w*token|jwt|auth\\w*)\\s*=(?!=)", false),
            // 다른 창의 메시지를 출처 확인 없이 받거나, 아무 출처에나 보냄
            rule("POSTMESSAGE", "CWE-346", "message 이벤트 수신", "addEventListener\\s*\\(\\s*['\"]message['\"]"),
            rule("POSTMESSAGE", "CWE-346", "postMessage(*, '*')", "\\.postMessage\\s*\\(.*,\\s*['\"]\\*['\"]"),
            // 코드에 박힌 비밀값 (빌드 결과물에 그대로 들어가서 누구나 볼 수 있다)
            secret("AWS 액세스 키", "\\bAKIA[0-9A-Z]{16}\\b"),
            secret("Google API 키", "\\bAIza[0-9A-Za-z_\\-]{35}\\b"),
            secret("Stripe 비밀 키", "\\b(?:sk|rk)_live_[0-9a-zA-Z]{16,}\\b"),
            secret("GitHub 토큰", "\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            secret("Slack 토큰", "\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            secret("LLM API 키", "\\bsk-(?:ant-|proj-)?[A-Za-z0-9_\\-]{20,}"),
            secret("개인 키", "-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
            secret("비밀값 대입", "(?i)\\b(?:api[_-]?key|api[_-]?secret|client[_-]?secret|secret[_-]?key|private[_-]?key|password|passwd)\\b\\s*[:=]\\s*['\"`][^'\"`\\s$]{8,}['\"`]")
    );

    private FrontendScanner() {
    }

    public static List<FrontendSink> scan(Path root) {
        List<FrontendSink> sinks = new ArrayList<>();
        for (Path file : files(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException | UncheckedIOException e) {
                continue;
            }
            for (int i = 0; i < lines.size() && sinks.size() < MAX_SINKS; i++) {
                String line = lines.get(i);
                if (line.length() > MAX_LINE_LENGTH || isComment(line)) {
                    continue;
                }
                for (Rule rule : RULES) {
                    Matcher m = rule.pattern().matcher(line);
                    if (m.find()) {
                        sinks.add(new FrontendSink(relative, i + 1, m.start() + 1, rule.category(), rule.cwe(), rule.api(),
                                snippet(line, rule.secret() ? m : null)));
                        // 한 줄에서는 처음 맞은 규칙 하나만
                        break;
                    }
                }
            }
        }
        return sinks;
    }

    /** 프론트엔드 코드 파일인지 (확장자) */
    public static boolean isFrontendFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase())
                && !fileName.endsWith(".min.js") && !fileName.endsWith(".d.ts")
                && !fileName.matches(".*\\.(?:test|spec|stories)\\.[a-z]+$");
    }

    private static List<Path> files(Path root) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> isFrontendFile(p.getFileName().toString()))
                    .filter(p -> !skipped(root.relativize(p)))
                    .filter(p -> p.toFile().length() <= MAX_FILE_BYTES)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("프론트엔드 파일을 찾지 못함: " + e.getMessage(), e);
        }
    }

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

    private static boolean isComment(String line) {
        String t = line.stripLeading();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("<!--");
    }

    /** 코드 한 줄. 비밀값 규칙이면 찾은 값의 앞 4글자만 남기고 가린다 */
    private static String snippet(String line, Matcher secret) {
        String text = line;
        if (secret != null) {
            String found = secret.group();
            String masked = found.length() <= 4 ? "****" : found.substring(0, 4) + "****(가림)";
            text = line.substring(0, secret.start()) + masked + line.substring(secret.end());
        }
        text = text.strip();
        return text.length() > SNIPPET_MAX_LENGTH ? text.substring(0, SNIPPET_MAX_LENGTH) + "..." : text;
    }

    private static Rule rule(String category, String cwe, String api, String regex) {
        return new Rule(category, cwe, api, Pattern.compile(regex), false);
    }

    private static Rule secret(String what, String regex) {
        return new Rule("HARDCODED_SECRET", "CWE-798", "하드코딩된 비밀값: " + what, Pattern.compile(regex), true);
    }

    private static Rule ruleIgnoreCase(String category, String cwe, String api, String regex, boolean secret) {
        return new Rule(category, cwe, api, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), secret);
    }
}
