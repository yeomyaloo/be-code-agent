package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 계획 담당 전용. 진입점 → 위험 지점 경로와 그 경로를 이미 다루는 할 일.
 */
@Component
@RequiredArgsConstructor
public class ListSinkPathsTool implements AgentTool {

    public static final String NAME = "list_sink_paths";

    private final PlanSupport plan;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "코드 그래프에서 외부 진입점(HTTP API)부터 위험 지점(SQL 실행, 명령 실행, 파일 접근, 역직렬화 등)까지 이어지는 호출 경로를 본다. "
                + "같은 (진입점, 위험 지점) 쌍은 가장 짧은 경로 하나만, 짧은 순으로 나온다. 이미 할 일이 있는 경로는 표시된다. "
                + "할 일을 만들 때 entry_node_id·sink_node_id 로 이 번호를 쓴다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of("limit", Map.of("type", "integer", "description", "최대 경로 수 (기본 40)"));
    }

    @Override
    public List<String> required() {
        return List.of();
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        int limit = (int) Math.min(200, Math.max(1, input.optionalLong("limit").orElse(40L)));
        List<SinkPathView> paths = plan.shortestPaths(context.projectId());
        if (paths.isEmpty()) {
            return "진입점에서 위험 지점까지 가는 경로가 없음. 인젝션 계열 경로가 없으면 list_entry_points 로 권한 검사 누락 같은 문제를 살펴볼 것";
        }
        StringBuilder sb = new StringBuilder("경로 ").append(paths.size()).append("개");
        if (paths.size() > limit) {
            sb.append(" 중 ").append(limit).append("개");
        }
        sb.append('\n');
        for (SinkPathView p : paths.stream().limit(limit).toList()) {
            Optional<AnalysisNode> existing = plan.intentionFor(context.jobId(), p.entryPoint().id(), p.sink().id());
            sb.append("- ").append(p.entryPoint().httpMethod()).append(' ').append(p.entryPoint().path())
                    .append(" (진입점 ").append(p.entryPoint().id()).append(") → [")
                    .append(p.sink().category()).append(' ').append(p.sink().cwe()).append("] ").append(p.sink().api())
                    .append(" (위험 지점 ").append(p.sink().id()).append(")")
                    .append(existing.map(n -> "  ← 할 일 #" + n.getId() + " 있음").orElse("")).append('\n');
            sb.append("  경로: ").append(String.join(" › ", p.path().stream()
                    .filter(n -> "METHOD".equals(n.kind()))
                    .map(n -> WorkerPrompts.shortName(n.qualifiedName()))
                    .toList())).append('\n');
        }
        return sb.toString();
    }
}
