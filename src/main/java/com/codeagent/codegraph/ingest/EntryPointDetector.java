package com.codeagent.codegraph.ingest;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.codeagent.codegraph.ingest.JavaProject.MethodInfo;
import com.codeagent.codegraph.ingest.JavaProject.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spring MVC 컨트롤러에서 HTTP 진입점을 찾는다.
 */
public class EntryPointDetector {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");

    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "");

    public record EntryPoint(String httpMethod, String path, MethodInfo handler) {

        public String qualifiedName() {
            return httpMethod + " " + path + " -> " + handler.key();
        }
    }

    public List<EntryPoint> detect(TypeInfo type) {
        if (findAnnotation(type.decl(), CONTROLLER_ANNOTATIONS).isEmpty()) {
            return List.of();
        }
        List<String> classPaths = findAnnotation(type.decl(), Set.of("RequestMapping"))
                .map(EntryPointDetector::paths)
                .orElse(List.of(""));

        List<EntryPoint> result = new ArrayList<>();
        for (MethodInfo method : type.methods()) {
            if (!(method.decl() instanceof MethodDeclaration decl)) {
                continue;
            }
            for (AnnotationExpr annotation : decl.getAnnotations()) {
                String fixedMethod = MAPPING_ANNOTATIONS.get(annotation.getName().getIdentifier());
                if (fixedMethod == null) {
                    continue;
                }
                List<String> httpMethods = fixedMethod.isEmpty() ? requestMethods(annotation) : List.of(fixedMethod);
                for (String classPath : classPaths) {
                    for (String methodPath : paths(annotation)) {
                        for (String httpMethod : httpMethods) {
                            result.add(new EntryPoint(httpMethod, joinPath(classPath, methodPath), method));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Optional<AnnotationExpr> findAnnotation(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream()
                .filter(a -> names.contains(a.getName().getIdentifier()))
                .findFirst();
    }

    private static List<String> paths(AnnotationExpr annotation) {
        List<String> paths = new ArrayList<>();
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            paths.addAll(values(single.getMemberValue()));
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String name = pair.getNameAsString();
                if (name.equals("value") || name.equals("path")) {
                    paths.addAll(values(pair.getValue()));
                }
            }
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<String> requestMethods(AnnotationExpr annotation) {
        List<String> methods = new ArrayList<>();
        if (annotation instanceof NormalAnnotationExpr normal) {
            normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("method"))
                    .forEach(p -> methods.addAll(values(p.getValue())));
        }
        return methods.isEmpty() ? List.of("ANY") : methods;
    }

    private static List<String> values(Expression expr) {
        if (expr instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().flatMap(v -> values(v).stream()).toList();
        }
        if (expr instanceof StringLiteralExpr literal) {
            return List.of(literal.getValue());
        }
        if (expr instanceof FieldAccessExpr field) {
            // RequestMethod.GET 은 GET 으로, 상수 경로는 ${Paths.USERS} 처럼 남긴다
            return List.of(field.getScope().toString().equals("RequestMethod")
                    ? field.getNameAsString() : "${" + field + "}");
        }
        if (expr instanceof NameExpr name) {
            String id = name.getNameAsString();
            return List.of(id.matches("GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE") ? id : "${" + id + "}");
        }
        return List.of("${" + expr + "}");
    }

    static String joinPath(String classPath, String methodPath) {
        String joined = ("/" + classPath + "/" + methodPath).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }
}
