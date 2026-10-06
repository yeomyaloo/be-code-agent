package com.codeagent.codegraph.ingest;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.resolution.types.ResolvedType;
import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;
import com.codeagent.codegraph.ingest.CodeGraphDraft.NodeDraft;
import com.codeagent.codegraph.ingest.EntryPointDetector.EntryPoint;
import com.codeagent.codegraph.ingest.JavaProject.MethodInfo;
import com.codeagent.codegraph.ingest.JavaProject.TypeInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 파싱된 프로젝트에서 코드 그래프(파일·클래스·메서드·진입점·위험 지점과 그 관계)를 만든다.
 */
@Slf4j
public class CodeGraphBuilder {

    private static final int SNIPPET_MAX_LENGTH = 300;

    private final JavaProject project;
    private final EntryPointDetector entryPointDetector = new EntryPointDetector();
    private final CodeGraphDraft draft = new CodeGraphDraft();

    public CodeGraphBuilder(JavaProject project) {
        this.project = project;
    }

    public CodeGraphDraft build() {
        project.types().forEach(this::addStructure);
        project.types().forEach(this::addOverrides);
        project.types().forEach(this::addEntryPoints);
        project.types().forEach(t -> t.methods().forEach(this::addCalls));
        return draft;
    }

    // ---- 구조: FILE → CLASS → METHOD ----

    private void addStructure(TypeInfo type) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, type.filePath(), type.filePath(), null, null, Map.of()));
        String classKey = draft.addNode(node(CodeNodeKind.CLASS, type.fqn(), type.filePath(), type.decl(), Map.of(
                "declaration", declarationKind(type.decl()),
                "annotations", annotations(type.decl()),
                "superTypes", type.superTypes())));

        Optional<String> outer = type.decl().getParentNode()
                .filter(p -> p instanceof TypeDeclaration<?>)
                .flatMap(p -> ((TypeDeclaration<?>) p).getFullyQualifiedName());
        draft.addEdge(outer.map(o -> CodeGraphDraft.key(CodeNodeKind.CLASS, o)).orElse(fileKey), classKey, CodeEdgeKind.CONTAINS);

        for (MethodInfo method : type.methods()) {
            String methodKey = draft.addNode(node(CodeNodeKind.METHOD, method.key(), type.filePath(), method.decl(), Map.of(
                    "name", method.name(),
                    "annotations", annotations(method.decl()))));
            draft.addEdge(classKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    private void addOverrides(TypeInfo type) {
        for (TypeInfo ancestor : project.ancestors(type)) {
            for (MethodInfo method : type.methods()) {
                if (method.isConstructor()) {
                    continue;
                }
                ancestor.methods().stream()
                        .filter(m -> m.name().equals(method.name()) && m.paramTypes().size() == method.paramTypes().size())
                        .forEach(m -> draft.addEdge(methodKey(m), methodKey(method), CodeEdgeKind.OVERRIDDEN_BY));
            }
        }
    }

    private void addEntryPoints(TypeInfo type) {
        for (EntryPoint entryPoint : entryPointDetector.detect(type)) {
            MethodInfo handler = entryPoint.handler();
            String entryKey = draft.addNode(node(CodeNodeKind.ENTRY_POINT, entryPoint.qualifiedName(), type.filePath(), handler.decl(), Map.of(
                    "httpMethod", entryPoint.httpMethod(),
                    "path", entryPoint.path(),
                    "handler", handler.key())));
            draft.addEdge(entryKey, methodKey(handler), CodeEdgeKind.ROUTES_TO);
        }
    }

    // ---- 호출 관계와 위험 지점 ----

    private void addCalls(MethodInfo caller) {
        for (MethodCallExpr call : caller.decl().findAll(MethodCallExpr.class)) {
            String targetType = call.getScope()
                    .map(scope -> typeOfScope(scope, caller))
                    .orElse(null);

            if (call.getScope().isEmpty()) {
                // 스코프 없는 호출: 자기 클래스 → 바깥 클래스 순으로 찾는다
                if (linkProjectCall(caller, enclosingTypes(caller), call)) {
                    continue;
                }
            } else if (project.isProjectType(targetType)) {
                if (linkProjectCall(caller, List.of(targetType), call)) {
                    continue;
                }
            }

            String method = call.getNameAsString();
            Optional<SinkRule> sink = SinkRules.match(targetType, method);
            if (sink.isPresent()) {
                addSink(caller, call, sink.get(), method);
            } else if (targetType == null) {
                draft.countUnresolvedCall();
                log.debug("호출 대상 타입을 알 수 없음: {} ({})", call, caller.key());
            }
        }

        for (ObjectCreationExpr creation : caller.decl().findAll(ObjectCreationExpr.class)) {
            String type = caller.owner().resolver().resolve(creation.getType().getNameWithScope());
            SinkRules.match(type, "<init>").ifPresent(rule -> addSink(caller, creation, rule, "<init>"));
        }
    }

    private boolean linkProjectCall(MethodInfo caller, List<String> candidateTypes, MethodCallExpr call) {
        for (String type : candidateTypes) {
            List<MethodInfo> targets = project.findMethods(type, call.getNameAsString(), call.getArguments().size());
            if (!targets.isEmpty()) {
                targets.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                return true;
            }
        }
        return false;
    }

    private void addSink(MethodInfo caller, Expression expr, SinkRule rule, String method) {
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        int column = expr.getBegin().map(p -> p.column).orElse(0);
        String api = rule.api(method);
        String snippet = expr.toString();
        if (snippet.length() > SNIPPET_MAX_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", rule.category());
        props.put("cwe", rule.cwe());
        props.put("api", api);
        props.put("method", caller.key());
        props.put("snippet", snippet);

        String sinkKey = draft.addNode(node(CodeNodeKind.SINK, caller.key() + "@L" + line + ":" + column + " " + api,
                caller.owner().filePath(), expr, props));
        draft.addEdge(methodKey(caller), sinkKey, CodeEdgeKind.CALLS);
    }

    /**
     * 호출 대상 표현식의 타입 FQN을 구한다.
     * 변수·필드 선언과 import 문을 먼저 보고(라이브러리 jar 없이 동작), 안 되면 symbol solver에 맡긴다.
     */
    private String typeOfScope(Expression scope, MethodInfo context) {
        TypeInfo owner = context.owner();
        if (scope.isThisExpr()) {
            return owner.fqn();
        }
        if (scope.isSuperExpr()) {
            return owner.superTypes().isEmpty() ? null : owner.superTypes().getFirst();
        }
        if (scope instanceof NameExpr name) {
            String variableType = variableType(name.getNameAsString(), context);
            if (variableType != null) {
                return variableType;
            }
            String staticType = owner.resolver().resolve(name.getNameAsString());
            if (staticType != null) {
                return staticType;
            }
        }
        if (scope instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            String fieldType = project.fieldType(owner, field.getNameAsString());
            if (fieldType != null) {
                return fieldType;
            }
        }
        return solve(scope);
    }

    private String variableType(String name, MethodInfo context) {
        TypeResolver resolver = context.owner().resolver();
        // 지역 변수 (var 면 초기값으로 추론)
        for (VariableDeclarator variable : context.decl().findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(name)) {
                if (variable.getType().isVarType()) {
                    return variable.getInitializer().map(this::solve).orElse(null);
                }
                return JavaProject.resolveType(variable.getType(), resolver);
            }
        }
        // 메서드·람다·catch 파라미터
        for (Parameter parameter : context.decl().findAll(Parameter.class)) {
            if (parameter.getNameAsString().equals(name) && !parameter.getType().isUnknownType()) {
                return JavaProject.resolveType(parameter.getType(), resolver);
            }
        }
        // 필드 (자기 클래스 → 바깥 클래스, 각각 상위 타입 포함)
        for (String typeFqn : enclosingTypes(context)) {
            String fieldType = project.type(typeFqn).map(t -> project.fieldType(t, name)).orElse(null);
            if (fieldType != null) {
                return fieldType;
            }
        }
        return null;
    }

    private List<String> enclosingTypes(MethodInfo method) {
        List<String> result = new ArrayList<>();
        Node current = method.owner().decl();
        while (current != null) {
            if (current instanceof TypeDeclaration<?> td) {
                td.getFullyQualifiedName().ifPresent(result::add);
            }
            current = current.getParentNode().orElse(null);
        }
        return result;
    }

    private String solve(Expression expr) {
        try {
            ResolvedType type = expr.calculateResolvedType();
            return type.isReferenceType() ? type.asReferenceType().getQualifiedName() : null;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    // ---- 헬퍼 ----

    private static String methodKey(MethodInfo method) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, method.key());
    }

    private static NodeDraft node(CodeNodeKind kind, String qualifiedName, String filePath, Node astNode, Map<String, Object> props) {
        Integer begin = astNode.getBegin().map(p -> p.line).orElse(null);
        Integer end = astNode.getEnd().map(p -> p.line).orElse(null);
        return new NodeDraft(kind, qualifiedName, filePath, begin, end, props);
    }

    private static List<String> annotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(a -> a.getName().getIdentifier()).toList();
    }

    private static String declarationKind(TypeDeclaration<?> decl) {
        if (decl.isClassOrInterfaceDeclaration()) {
            return decl.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        }
        if (decl.isEnumDeclaration()) {
            return "enum";
        }
        if (decl.isRecordDeclaration()) {
            return "record";
        }
        return "annotation";
    }
}
