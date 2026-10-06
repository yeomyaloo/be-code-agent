package com.codeagent.agent.tool;

import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GetCalleesTool implements AgentTool {

    private final CodeGraphQuery codeGraphQuery;
    private final CodeNodeResolver resolver;

    @Override
    public String name() {
        return "get_callees";
    }

    @Override
    public String description() {
        return "이 메서드(또는 진입점)가 호출하는 메서드와 위험 지점(SINK)을 찾는다. "
                + "인터페이스 메서드면 구현 메서드도 나온다. 라이브러리 호출은 위험 지점으로 분류된 것만 나온다.";
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
        return "대상: " + CodeNodeResolver.format(List.of(node)) + "\n호출 대상:\n"
                + CodeNodeResolver.format(codeGraphQuery.callees(node.id()));
    }
}
