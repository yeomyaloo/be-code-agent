package com.codeagent.agent.tool;

import com.codeagent.codegraph.query.CodeGraphQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FindCodeNodesTool implements AgentTool {

    private static final int LIMIT = 30;
    private static final Set<String> KINDS = Set.of("FILE", "CLASS", "METHOD", "ENTRY_POINT", "SINK");

    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return "find_code_nodes";
    }

    @Override
    public String description() {
        return "코드 그래프에서 이름 일부로 노드(파일·클래스·메서드·진입점·위험 지점)를 찾는다. "
                + "메서드 이름 형식은 'com.x.UserService#find(String)'. 결과의 [id]를 get_callers/get_callees에 쓴다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of(
                "query", Map.of("type", "string", "description", "이름에 포함된 문자열 (대소문자 무시, 예: UserService#find)"),
                "kind", Map.of("type", "string", "enum", List.copyOf(KINDS), "description", "노드 종류로 거르기 (생략하면 전체)"));
    }

    @Override
    public List<String> required() {
        return List.of("query");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        String kind = input.optionalString("kind").orElse(null);
        if (kind != null && !KINDS.contains(kind)) {
            throw new ToolInputException("kind는 " + KINDS + " 중 하나여야 함");
        }
        return CodeNodeResolver.format(codeGraphQuery.findNodes(context.projectId(), input.string("query"), kind, LIMIT));
    }
}
