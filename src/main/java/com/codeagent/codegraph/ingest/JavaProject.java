package com.codeagent.codegraph.ingest;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 저장소의 Java 소스를 파싱해서 타입·메서드 색인을 만든다.
 * 라이브러리 jar 없이 소스만으로 동작한다. JDK 타입은 리플렉션으로, 프로젝트 타입은 소스로 해석한다.
 */
public class JavaProject {

    private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", ".idea", "build", "target", "out", "node_modules");

    private final Path root;
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final List<String> parseErrors = new ArrayList<>();

    private JavaProject(Path root) {
        this.root = root;
    }

    public static JavaProject load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("저장소 경로가 디렉터리가 아님: " + root);
        }
        JavaProject project = new JavaProject(root.toAbsolutePath().normalize());
        project.parse();
        return project;
    }

    public Collection<TypeInfo> types() {
        return types.values();
    }

    public Optional<TypeInfo> type(String fqn) {
        return Optional.ofNullable(fqn).map(types::get);
    }

    public boolean isProjectType(String fqn) {
        return fqn != null && types.containsKey(fqn);
    }

    public List<String> parseErrors() {
        return parseErrors;
    }

    /** 프로젝트 안에 있는 모든 상위 타입 (자기 자신 제외, 가까운 순) */
    public List<TypeInfo> ancestors(TypeInfo type) {
        List<TypeInfo> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(type.fqn()));
        Deque<String> queue = new ArrayDeque<>(type.superTypes());
        while (!queue.isEmpty()) {
            String fqn = queue.poll();
            if (!visited.add(fqn)) {
                continue;
            }
            TypeInfo ancestor = types.get(fqn);
            if (ancestor != null) {
                result.add(ancestor);
                queue.addAll(ancestor.superTypes());
            }
        }
        return result;
    }

    /** 타입과 그 상위 타입에서 이름·인자 수가 맞는 메서드를 찾는다. 가장 가까운 타입에서 찾은 것만 돌려준다. */
    public List<MethodInfo> findMethods(String typeFqn, String name, int argCount) {
        TypeInfo type = types.get(typeFqn);
        if (type == null) {
            return List.of();
        }
        List<TypeInfo> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (TypeInfo candidate : candidates) {
            List<MethodInfo> found = candidate.methods().stream()
                    .filter(m -> m.name().equals(name) && m.accepts(argCount))
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    /** 타입(과 상위 타입)에 선언된 필드의 타입 FQN */
    public String fieldType(TypeInfo type, String fieldName) {
        List<TypeInfo> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (TypeInfo candidate : candidates) {
            Optional<FieldDeclaration> field = candidate.decl().getFieldByName(fieldName);
            if (field.isPresent()) {
                Type declared = field.get().getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(fieldName))
                        .findFirst().orElseThrow().getType();
                return resolveType(declared, candidate.resolver());
            }
        }
        return null;
    }

    public static String resolveType(Type type, TypeResolver resolver) {
        return type instanceof ClassOrInterfaceType c ? resolver.resolve(c.getNameWithScope()) : null;
    }

    private void parse() {
        List<Path> sourceRoots = findSourceRoots();
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
        sourceRoots.forEach(r -> typeSolver.add(new JavaParserTypeSolver(r, config)));
        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(config);

        Map<CompilationUnit, String> units = new LinkedHashMap<>();
        for (Path file : findJavaFiles(sourceRoots)) {
            String relativePath = root.relativize(file).toString().replace('\\', '/');
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (!result.isSuccessful()) {
                    parseErrors.add(relativePath + ": " + result.getProblems().getFirst().getMessage());
                }
                result.getResult().ifPresent(cu -> units.put(cu, relativePath));
            } catch (IOException e) {
                parseErrors.add(relativePath + ": " + e.getMessage());
            }
        }

        // 1단계: 프로젝트 타입 이름 수집
        Set<String> projectTypes = units.keySet().stream()
                .flatMap(cu -> cu.findAll(TypeDeclaration.class).stream())
                .map(td -> ((TypeDeclaration<?>) td).getFullyQualifiedName().orElse(null))
                .filter(fqn -> fqn != null)
                .collect(Collectors.toSet());

        // 2단계: 타입·상위 타입·메서드 색인
        units.forEach((cu, relativePath) -> {
            TypeResolver resolver = new TypeResolver(cu, projectTypes);
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                td.getFullyQualifiedName().ifPresent(fqn -> {
                    TypeInfo type = new TypeInfo(fqn, td, relativePath, resolver, superTypesOf(td, resolver), new ArrayList<>());
                    td.getMembers().stream()
                            .filter(m -> m instanceof CallableDeclaration<?>)
                            .map(m -> (CallableDeclaration<?>) m)
                            .forEach(c -> type.methods().add(MethodInfo.of(type, c)));
                    types.put(fqn, type);
                });
            }
        });
    }

    private static List<String> superTypesOf(TypeDeclaration<?> td, TypeResolver resolver) {
        Set<String> result = new LinkedHashSet<>();
        if (td instanceof NodeWithExtends<?> withExtends) {
            withExtends.getExtendedTypes().forEach(t -> add(result, resolver.resolve(t.getNameWithScope())));
        }
        if (td instanceof NodeWithImplements<?> withImplements) {
            withImplements.getImplementedTypes().forEach(t -> add(result, resolver.resolve(t.getNameWithScope())));
        }
        return List.copyOf(result);
    }

    private static void add(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
    }

    private List<Path> findSourceRoots() {
        try (Stream<Path> dirs = walk()) {
            List<Path> roots = dirs.filter(Files::isDirectory)
                    .filter(d -> d.endsWith(Path.of("src", "main", "java")))
                    .toList();
            return roots.isEmpty() ? List.of(root) : roots;
        }
    }

    private List<Path> findJavaFiles(List<Path> sourceRoots) {
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> !isSkipped(sourceRoot.relativize(p)))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private Stream<Path> walk() {
        try {
            return Files.walk(root).filter(p -> !isSkipped(root.relativize(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isSkipped(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    public record TypeInfo(String fqn, TypeDeclaration<?> decl, String filePath, TypeResolver resolver,
                           List<String> superTypes, List<MethodInfo> methods) {

        // methods → owner 순환 참조가 있어서 equals/hashCode/toString은 이름으로만 계산한다
        @Override
        public boolean equals(Object o) {
            return o instanceof TypeInfo other && fqn.equals(other.fqn);
        }

        @Override
        public int hashCode() {
            return fqn.hashCode();
        }

        @Override
        public String toString() {
            return fqn;
        }
    }

    public record MethodInfo(String key, String name, List<String> paramTypes, boolean varargs,
                             CallableDeclaration<?> decl, TypeInfo owner) {

        static MethodInfo of(TypeInfo owner, CallableDeclaration<?> decl) {
            String name = decl instanceof ConstructorDeclaration ? "<init>" : decl.getNameAsString();
            List<String> paramTypes = decl.getParameters().stream()
                    .map(p -> typeName(p.getType()) + (p.isVarArgs() ? "..." : ""))
                    .toList();
            boolean varargs = decl.getParameters().stream().anyMatch(Parameter::isVarArgs);
            String key = owner.fqn() + "#" + name + "(" + String.join(",", paramTypes) + ")";
            return new MethodInfo(key, name, paramTypes, varargs, decl, owner);
        }

        public boolean accepts(int argCount) {
            int params = paramTypes.size();
            return varargs ? argCount >= params - 1 : argCount == params;
        }

        public boolean isConstructor() {
            return "<init>".equals(name);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MethodInfo other && key.equals(other.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public String toString() {
            return key;
        }

        private static String typeName(Type type) {
            if (type instanceof ArrayType array) {
                return typeName(array.getComponentType()) + "[]";
            }
            if (type instanceof ClassOrInterfaceType c) {
                return c.getNameWithScope();
            }
            return type.asString();
        }
    }
}
