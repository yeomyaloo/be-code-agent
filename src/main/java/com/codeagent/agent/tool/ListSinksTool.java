package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 계획 담당 전용. 위험 지점 전체 목록. 진입점 → 위험 지점 경로로 이어지지 않는 것(프론트엔드 JS/TS 코드,
 * 어디서도 호출되지 않아 보이는 코드)도 나온다.
 */
@Component
@RequiredArgsConstructor
public class ListSinksTool implements AgentTool {

    public static final String NAME = "list_sinks";
    private static final int LIMIT = 200;

    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "위험 지점 전체 목록 (분류, CWE, API, 위치, 코드 한 줄). 진입점 경로가 없는 위험 지점도 나온다. "
                + "프론트엔드(JS/TS/Vue/HTML) 위험 지점은 [프론트엔드]로 표시되고 HTTP 진입점 경로가 없으므로, "
                + "파일별로 묶어 할 일로 만든다. 분류: XSS, CODE_INJECTION, OPEN_REDIRECT, TOKEN_STORAGE, POSTMESSAGE, HARDCODED_SECRET 등.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of(
                "category", Map.of("type", "string", "description", "분류로 거르기 (예: XSS)"),
                "frontend_only", Map.of("type", "boolean", "description", "true 면 프론트엔드 위험 지점만"));
    }

    @Override
    public List<String> required() {
        return List.of();
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        String category = input.optionalString("category").map(c -> c.toUpperCase(Locale.ROOT)).orElse(null);
        boolean frontendOnly = input.optionalBoolean("frontend_only").orElse(false);
        List<SinkView> sinks = codeGraphQuery.sinks(context.projectId()).stream()
                .filter(s -> category == null || category.equals(s.category()))
                .filter(s -> !frontendOnly || s.frontend())
                .toList();
        if (sinks.isEmpty()) {
            return "위험 지점 없음";
        }
        String body = sinks.stream().limit(LIMIT)
                .map(s -> "- " + (s.frontend() ? "[프론트엔드] " : "") + "[" + s.category() + " " + s.cwe() + "] " + s.api()
                        + " (노드 " + s.id() + ") · " + WorkerPrompts.shortName(s.method()) + " · " + s.filePath() + ":" + s.line()
                        + "\n    " + s.snippet())
                .collect(Collectors.joining("\n"));
        return "위험 지점 " + sinks.size() + "개" + (sinks.size() > LIMIT ? " (앞의 " + LIMIT + "개만)" : "") + "\n" + body;
    }
}
