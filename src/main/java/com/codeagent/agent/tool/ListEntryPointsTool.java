package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 계획 담당 전용. HTTP 진입점 전체 목록.
 */
@Component
@RequiredArgsConstructor
public class ListEntryPointsTool implements AgentTool {

    public static final String NAME = "list_entry_points";

    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Spring MVC 컨트롤러에서 찾은 HTTP 진입점 전체 목록 (메서드, 경로, 처리 메서드, 위치). "
                + "위험 지점까지 이어지지 않는 진입점도 나온다. 경로 변수({id})로 데이터를 조회·수정·삭제하는 API 는 "
                + "소유자 확인 누락(IDOR, CWE-639) 같은 권한 문제를 점검할 후보다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of();
    }

    @Override
    public List<String> required() {
        return List.of();
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        List<EntryPointView> entries = codeGraphQuery.entryPoints(context.projectId());
        if (entries.isEmpty()) {
            return "진입점 없음";
        }
        return "진입점 " + entries.size() + "개\n" + entries.stream()
                .map(e -> "- " + e.httpMethod() + " " + e.path() + " (노드 " + e.id() + ") · "
                        + WorkerPrompts.shortName(e.handler()) + " · " + e.filePath() + ":" + e.line())
                .collect(Collectors.joining("\n"));
    }
}
