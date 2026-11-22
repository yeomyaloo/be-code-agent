package com.codeagent.codegraph.ingest;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
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
        addFrontendSinks();
        return draft;
    }

    // ---- 프론트엔드(JS/TS) 위험 지점: 파일 → 위험 지점. 진입점에서 이어지는 경로는 없다 ----

    private void addFrontendSinks() {
        for (FrontendScanner.FrontendSink sink : FrontendScanner.scan(project.root())) {
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, sink.filePath(), sink.filePath(), null, null,
                    Map.of("language", "javascript")));
            String fileName = sink.filePath().substring(sink.filePath().lastIndexOf('/') + 1);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", sink.category());
            props.put("cwe", sink.cwe());
            props.put("api", sink.api());
            // 함수 단위로 나누지 않으므로 파일 이름(확장자 제외)을 위치 이름으로 쓴다
            props.put("method", fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName);
            props.put("snippet", sink.snippet());
            props.put("frontend", true);
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK,
                    sink.filePath() + "@L" + sink.line() + ":" + sink.column() + " " + sink.api(),
                    sink.filePath(), sink.line(), sink.line(), props));
            draft.addEdge(fileKey, sinkKey, CodeEdgeKind.CONTAINS);
        }
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

            // 저장소·매퍼가 프로젝트에 선언한 메서드도 id 하나로 데이터를 다루면 IDOR 후보다
            Optional<SinkRule> idor = idorCandidate(caller, call, targetType);
            if (idor.isPresent()) {
                addSink(caller, call, idor.get(), call.getNameAsString());
                continue;
            }

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
            // 저장소의 findById 등은 상속받은 라이브러리 메서드라 프로젝트 호출로 이어지지 않는다
            Optional<SinkRule> sink = SinkRules.match(targetType, method)
                    .or(() -> project.isSpringDataRepository(targetType)
                            ? SinkRules.repositoryIdAccess(targetType, method) : Optional.empty());
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

    /**
     * IDOR 후보:
     * - Spring Data 저장소에 선언한 메서드: 조건이 식별자 하나뿐인 파생 쿼리(findByBuyerId), 식별자 인자 하나만 받는 @Query 메서드
     * - MyBatis 매퍼(@Mapper) 메서드: 식별자 인자 하나만 받는 것 (주인 조건 인자가 같이 있으면 제외)
     * - Spring Data save 에 @RequestBody·@ModelAttribute 로 받은 객체를 그대로 넘기는 호출 (본문의 id 로 남의 데이터를 덮어씀)
     */
    private Optional<SinkRule> idorCandidate(MethodInfo caller, MethodCallExpr call, String targetType) {
        if (targetType == null) {
            return Optional.empty();
        }
        String method = call.getNameAsString();
        boolean repository = project.isSpringDataRepository(targetType);
        boolean mapper = project.type(targetType)
                .map(t -> t.decl().getAnnotations().stream().anyMatch(a -> isAnnotation(t, a.getNameAsString(), SinkRules.MYBATIS_MAPPER)))
                .orElse(false);
        if (!repository && !mapper) {
            return Optional.empty();
        }
        if (repository && SinkRules.SAVE_METHODS.contains(method) && call.getArguments().size() == 1
                && call.getArgument(0) instanceof NameExpr arg && isRequestBodyParameter(caller, arg.getNameAsString())) {
            return Optional.of(SinkRules.idorCandidate(targetType, method));
        }
        List<MethodInfo> declared = project.findMethods(targetType, method, call.getArguments().size());
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        CallableDeclaration<?> decl = declared.getFirst().decl();
        if (decl.getParameters().size() != 1) {
            return Optional.empty();
        }
        Parameter parameter = decl.getParameter(0);
        boolean idParameter = SinkRules.idLikeName(paramName(parameter));
        boolean candidate = mapper
                ? idParameter
                : SinkRules.singleIdCondition(method) || (decl.getAnnotationByName("Query").isPresent() && idParameter);
        return candidate ? Optional.of(SinkRules.idorCandidate(targetType, method)) : Optional.empty();
    }

    /** @Param("orderId") 가 있으면 그 이름, 없으면 파라미터 이름 */
    private static String paramName(Parameter parameter) {
        return parameter.getAnnotationByName("Param")
                .flatMap(a -> a.isSingleMemberAnnotationExpr()
                        ? Optional.of(a.asSingleMemberAnnotationExpr().getMemberValue())
                        : a.isNormalAnnotationExpr()
                        ? a.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals("value")).findFirst().map(p -> p.getValue())
                        : Optional.empty())
                .filter(e -> e.isStringLiteralExpr())
                .map(e -> e.asStringLiteralExpr().asString())
                .orElse(parameter.getNameAsString());
    }

    private static boolean isRequestBodyParameter(MethodInfo caller, String name) {
        return caller.decl().getParameters().stream()
                .anyMatch(p -> p.getNameAsString().equals(name)
                        && (p.getAnnotationByName("RequestBody").isPresent() || p.getAnnotationByName("ModelAttribute").isPresent()));
    }

    private static boolean isAnnotation(TypeInfo type, String simpleOrQualified, String fqn) {
        if (simpleOrQualified.equals(fqn)) {
            return true;
        }
        return fqn.endsWith("." + simpleOrQualified) && fqn.equals(type.resolver().resolve(simpleOrQualified));
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
        // new SAXReader().read(...) 처럼 만들자마자 부르는 호출
        if (scope instanceof ObjectCreationExpr creation) {
            String createdType = JavaProject.resolveType(creation.getType(), owner.resolver());
            if (createdType != null) {
                return createdType;
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
