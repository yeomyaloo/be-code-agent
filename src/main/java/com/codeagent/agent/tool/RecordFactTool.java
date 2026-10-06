package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnchorRole;
import com.codeagent.codegraph.query.CodeGraphQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RecordFactTool implements AgentTool {

    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return "record_fact";
    }

    @Override
    public String description() {
        return "코드를 읽고 확인한 사실 하나를 분석 그래프에 기록한다. 다른 실행 담당도 이 기록을 검색한다. "
                + "예: 'UserServiceImpl.findByName이 name 파라미터를 SQL 문자열에 그대로 이어 붙임'. "
                + "추측이 아니라 코드에서 직접 확인한 것만 기록한다. 돌려받은 fact id는 record_finding의 fact_ids에 쓴다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of(
                "title", Map.of("type", "string", "description", "사실 한 문장 (한국어)"),
                "detail", Map.of("type", "string", "description", "근거: 파일·줄 번호와 관련 코드 설명"),
                "code_node_ids", Map.of("type", "array", "items", Map.of("type", "integer"),
                        "description", "이 사실과 관련된 코드 그래프 노드 id들"));
    }

    @Override
    public List<String> required() {
        return List.of("title", "detail");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        List<Long> codeNodeIds = input.longList("code_node_ids");
        codeNodeIds.forEach(id -> codeGraphQuery.node(context.projectId(), id)
                .orElseThrow(() -> new ToolInputException("코드 노드 없음: " + id)));

        AnalysisNode fact = analysisGraph.addNode(context.jobId(), AnalysisNodeKind.FACT,
                input.string("title"), input.string("detail"), Map.of("workerId", context.workerId()));
        analysisGraph.complete(fact.getId());
        analysisGraph.addEdge(context.jobId(), context.intentionId(), fact.getId(), AnalysisEdgeKind.YIELDS);
        analysisGraph.anchor(fact.getId(), codeNodeIds, AnchorRole.LOCATED_AT);
        return "fact #" + fact.getId() + " 기록함";
    }
}
