package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 계획 담당 전용. 공유 할 일 목록에 할 일을 더한다. 실행 담당이 가져가 조사한다.
 */
@Component
@RequiredArgsConstructor
public class AddIntentionTool implements AgentTool {

    public static final String NAME = "add_intention";

    private final PlanSupport plan;
    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "공유 할 일 목록에 할 일 하나를 더한다. 실행 담당(Worker)이 가져가 instructions 대로 코드를 조사한다. "
                + "진입점 → 위험 지점 경로를 조사시키려면 entry_node_id 와 sink_node_id 를 함께 준다(list_sink_paths 의 번호). "
                + "경로가 없는 조사(예: 소유자 확인 누락, 보안 설정, 하드코딩된 비밀값)는 instructions 만으로 만든다. "
                + "앞선 할 일의 결과가 있어야 하는 일은 depends_on 에 그 할 일 번호를 넣으면 그 일이 끝난 뒤에 시작된다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "할 일 제목 (예: 게시글 수정·삭제 API의 작성자 확인 누락 점검)"));
        props.put("instructions", Map.of("type", "string", "description",
                "실행 담당에게 줄 구체적인 지시: 무엇을, 어느 파일·메서드부터, 무엇을 확인해야 하는지"));
        props.put("entry_node_id", Map.of("type", "integer", "description", "조사할 진입점 노드 번호 (선택)"));
        props.put("sink_node_id", Map.of("type", "integer", "description", "조사할 위험 지점 노드 번호 (선택)"));
        props.put("category", Map.of("type", "string", "description",
                "분류 (예: SQL, COMMAND, PATH, AUTHZ, CONFIG, SECRET). 위험 지점을 주면 생략해도 된다"));
        props.put("cwe", Map.of("type", "string", "description", "예상 CWE (예: CWE-639). 위험 지점을 주면 생략해도 된다"));
        props.put("depends_on", Map.of("type", "array", "items", Map.of("type", "integer"),
                "description", "먼저 끝나야 하는 할 일 번호들"));
        return props;
    }

    @Override
    public List<String> required() {
        return List.of("title", "instructions");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        AnalysisNode goal = plan.goal(context);
        List<AnalysisNode> existing = plan.nodes(context.jobId(), AnalysisNodeKind.INTENTION);
        int max = plan.maxIntentions(context);
        if (existing.size() >= max) {
            throw new ToolInputException("할 일 상한 " + max + "개에 도달함. 더 중요한 일이 없으면 finish_planning 으로 계획을 끝낼 것");
        }

        Long entryId = input.optionalLong("entry_node_id").orElse(null);
        Long sinkId = input.optionalLong("sink_node_id").orElse(null);
        CodeNodeView entry = entryId == null ? null : requireNode(context, entryId, "ENTRY_POINT");
        CodeNodeView sink = sinkId == null ? null : requireNode(context, sinkId, "SINK");
        SinkPathView path = null;
        if (entry != null && sink != null) {
            plan.intentionFor(context.jobId(), entryId, sinkId).ifPresent(n -> {
                throw new ToolInputException("이 경로는 이미 할 일 #" + n.getId() + " 이 다룸");
            });
            path = plan.path(context.projectId(), entryId, sinkId).orElseThrow(() -> new ToolInputException(
                    "진입점 " + entryId + " 에서 위험 지점 " + sinkId + " 까지 가는 경로가 코드 그래프에 없음. "
                            + "경로 없이 조사시키려면 둘 중 하나만 주거나 instructions 에 적을 것"));
        }

        Set<Long> existingIds = existing.stream().map(AnalysisNode::getId).collect(Collectors.toSet());
        List<Long> dependsOn = input.longList("depends_on");
        for (Long dep : dependsOn) {
            if (!existingIds.contains(dep)) {
                throw new ToolInputException("depends_on 의 할 일이 없음: #" + dep);
            }
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entryNodeId", entryId);
        props.put("sinkNodeId", sinkId);
        props.put("category", input.optionalString("category").orElse(path != null ? path.sink().category() : null));
        props.put("cwe", input.optionalString("cwe").orElse(path != null ? path.sink().cwe() : null));
        props.put("instructions", input.string("instructions"));
        props.put("plannedRound", plan.round(context));
        props.put("dependsOn", dependsOn);

        List<Long> examined = path == null ? List.of() : path.path().stream()
                .filter(n -> "METHOD".equals(n.kind())).map(CodeNodeView::id).toList();
        AnalysisNode intention = analysisGraph.addIntention(context.jobId(), goal.getId(), input.string("title"),
                WorkerPrompts.planned(input.string("instructions"), path), props, entryId, sinkId, examined, dependsOn);
        return "할 일 #" + intention.getId() + " 추가함 (" + plan.round(context) + "회차, 남은 자리 " + (max - existing.size() - 1) + "개)";
    }

    private CodeNodeView requireNode(ToolContext context, Long id, String kind) {
        CodeNodeView node = codeGraphQuery.node(context.projectId(), id)
                .orElseThrow(() -> new ToolInputException("코드 노드 없음: " + id));
        if (!kind.equals(node.kind())) {
            throw new ToolInputException("노드 " + id + " 는 " + kind + " 가 아니라 " + node.kind() + " 임");
        }
        return node;
    }
}
