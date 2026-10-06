package com.codeagent.agent.tool;

import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GetCallersTool implements AgentTool {

    private final CodeGraphQuery codeGraphQuery;
    private final CodeNodeResolver resolver;

    @Override
    public String name() {
        return "get_callers";
    }

    @Override
    public String description() {
        return "이 메서드를 호출하는 쪽을 찾는다. HTTP 진입점(ENTRY_POINT), 호출하는 메서드, "
                + "이 메서드가 재정의한 상위 타입 메서드(인터페이스를 통한 호출)가 나온다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of("node", Map.of("type", "string", "description", "노드 id 또는 메서드 이름 일부"));
    }

    @Override
    public List<String> required() {
        return List.of("node");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        CodeNodeView node = resolver.resolve(input, context);
        return "대상: " + CodeNodeResolver.format(List.of(node)) + "\n호출하는 쪽:\n"
                + CodeNodeResolver.format(codeGraphQuery.callers(node.id()));
    }
}
