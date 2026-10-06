package com.codeagent.codegraph.ingest;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 소스에 적힌 타입 이름을 import 문 기준으로 정규화된 이름(FQN)으로 바꾼다.
 * 라이브러리 jar 없이도 Spring·JPA 타입 이름을 알아낼 수 있게 하려는 용도다.
 */
public class TypeResolver {

    private static final Map<String, Boolean> JDK_TYPE_CACHE = new ConcurrentHashMap<>();

    private final String packageName;
    private final Set<String> projectTypes;
    private final Map<String, String> singleImports = new HashMap<>();
    private final List<String> wildcardImports = new ArrayList<>();
    private final Map<String, String> localTypes = new HashMap<>();

    public TypeResolver(CompilationUnit cu, Set<String> projectTypes) {
        this.packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        this.projectTypes = projectTypes;
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                wildcardImports.add(name);
            } else {
                singleImports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            td.getFullyQualifiedName().ifPresent(fqn -> localTypes.putIfAbsent(td.getNameAsString(), fqn));
        }
    }

    /**
     * @param name 소스에 적힌 타입 이름 (예: {@code JdbcTemplate}, {@code Map.Entry}, {@code List<String>})
     * @return 정규화된 이름, 알 수 없으면 null
     */
    public String resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        name = stripGenerics(name);
        int dot = name.indexOf('.');
        String head = dot < 0 ? name : name.substring(0, dot);
        String tail = dot < 0 ? "" : name.substring(dot);
        String headFqn = resolveSimple(head);
        if (headFqn != null) {
            return headFqn + tail;
        }
        // 소문자로 시작하면 이미 패키지가 붙은 이름으로 본다 (예: java.util.List)
        return dot > 0 && Character.isLowerCase(head.charAt(0)) ? name : null;
    }

    private String resolveSimple(String simple) {
        String local = localTypes.get(simple);
        if (local != null) {
            return local;
        }
        String imported = singleImports.get(simple);
        if (imported != null) {
            return imported;
        }
        String samePackage = packageName.isEmpty() ? simple : packageName + "." + simple;
        if (projectTypes.contains(samePackage)) {
            return samePackage;
        }
        for (String pkg : wildcardImports) {
            String candidate = pkg + "." + simple;
            if (projectTypes.contains(candidate) || isJdkType(candidate)) {
                return candidate;
            }
        }
        String javaLang = "java.lang." + simple;
        return isJdkType(javaLang) ? javaLang : null;
    }

    static String stripGenerics(String name) {
        int lt = name.indexOf('<');
        String result = lt < 0 ? name : name.substring(0, lt);
        return result.replace("[]", "").replace("...", "").trim();
    }

    private static boolean isJdkType(String fqn) {
        return JDK_TYPE_CACHE.computeIfAbsent(fqn, n -> {
            try {
                Class.forName(n, false, ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }
}
