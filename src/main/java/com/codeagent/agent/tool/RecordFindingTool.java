package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnchorRole;
import com.codeagent.codegraph.query.CodeGraphQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RecordFindingTool implements AgentTool {

    private static final Set<String> SEVERITIES = Set.of("critical", "high", "medium", "low", "info");
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");

    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return "record_finding";
    }

    @Override
    public String description() {
        return "공격 가능한 취약점을 발견으로 기록한다. 외부 입력이 검증·이스케이프 없이 위험 지점까지 도달하는 것을 "
                + "코드로 확인했을 때만 쓴다. 기록된 발견은 별도의 검증 담당이 다시 확인한다. "
                + "근거가 된 사실(record_fact로 기록한 것)의 id를 fact_ids로 연결한다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "취약점 제목 (한국어, 예: 사용자 검색 API의 SQL 인젝션)"));
        props.put("cwe", Map.of("type", "string", "description", "CWE 번호 (예: CWE-89)"));
        props.put("severity", Map.of("type", "string", "enum", List.copyOf(SEVERITIES)));
        props.put("confidence", Map.of("type", "string", "enum", List.copyOf(CONFIDENCES),
                "description", "공격 가능하다는 확신 정도"));
        props.put("description", Map.of("type", "string", "description", "입력이 어디서 들어와 어떤 경로로 위험 지점에 닿는지"));
        props.put("exploit_scenario", Map.of("type", "string", "description", "구체적인 공격 요청 예시"));
        props.put("evidence", Map.of("type", "array", "description", "근거 코드 위치",
                "items", Map.of("type", "object",
                        "properties", Map.of(
                                "file", Map.of("type", "string"),
                                "line", Map.of("type", "integer"),
                                "note", Map.of("type", "string")),
                        "required", List.of("file", "line", "note"))));
        props.put("source_node_id", Map.of("type", "integer", "description", "입력이 들어오는 진입점(ENTRY_POINT) 노드 id"));
        props.put("sink_node_id", Map.of("type", "integer", "description", "위험 지점(SINK) 노드 id"));
        props.put("fact_ids", Map.of("type", "array", "items", Map.of("type", "integer"),
                "description", "이 발견을 뒷받침하는 fact id들"));
        return props;
    }

    @Override
    public List<String> required() {
        return List.of("title", "cwe", "severity", "confidence", "description", "evidence");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        List<Map<String, Object>> evidence = input.objectList("evidence");
        if (evidence.isEmpty()) {
            throw new ToolInputException("evidence에 근거 코드 위치가 하나 이상 있어야 함");
        }
        Long sourceId = input.optionalLong("source_node_id").orElse(null);
        Long sinkId = input.optionalLong("sink_node_id").orElse(null);
        for (Long id : new Long[]{sourceId, sinkId}) {
            if (id != null && codeGraphQuery.node(context.projectId(), id).isEmpty()) {
                throw new ToolInputException("코드 노드 없음: " + id);
            }
        }
        List<Long> factIds = input.longList("fact_ids");
        for (Long factId : factIds) {
            try {
                analysisGraph.requireNodeInJob(context.jobId(), factId, AnalysisNodeKind.FACT);
            } catch (NoSuchElementException e) {
                throw new ToolInputException(e.getMessage());
            }
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cwe", input.string("cwe"));
        props.put("severity", input.oneOf("severity", SEVERITIES));
        props.put("confidence", input.oneOf("confidence", CONFIDENCES));
        props.put("exploitScenario", input.optionalString("exploit_scenario").orElse(null));
        props.put("evidence", evidence);
        props.put("sourceNodeId", sourceId);
        props.put("sinkNodeId", sinkId);
        props.put("workerId", context.workerId());

        // 발견은 OPEN 상태로 남겨 두고, 검증 담당이 CONFIRMED / REJECTED 로 바꾼다
        AnalysisNode finding = analysisGraph.addNode(context.jobId(), AnalysisNodeKind.FINDING,
                input.string("title"), input.string("description"), props);
        analysisGraph.addEdge(context.jobId(), context.intentionId(), finding.getId(), AnalysisEdgeKind.YIELDS);
        factIds.forEach(factId -> analysisGraph.addEdge(context.jobId(), factId, finding.getId(), AnalysisEdgeKind.PROVES));
        if (sourceId != null) {
            analysisGraph.anchor(finding.getId(), List.of(sourceId), AnchorRole.SOURCE);
        }
        if (sinkId != null) {
            analysisGraph.anchor(finding.getId(), List.of(sinkId), AnchorRole.SINK);
        }
        return "finding #" + finding.getId() + " 기록함 (검증 대기)";
    }
}
