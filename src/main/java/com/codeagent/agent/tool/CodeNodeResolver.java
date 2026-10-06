package com.codeagent.agent.tool;

import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 도구 입력의 node(노드 id 또는 이름 일부)를 코드 그래프 노드 하나로 바꾼다.
 */
@Component
@RequiredArgsConstructor
class CodeNodeResolver {

    private static final int CANDIDATE_LIMIT = 10;

    private final CodeGraphQuery codeGraphQuery;

    CodeNodeView resolve(ToolInput input, ToolContext context) {
        String node = input.string("node");
        if (node.matches("\\d+")) {
            return codeGraphQuery.node(context.projectId(), Long.parseLong(node))
                    .orElseThrow(() -> new ToolInputException("노드 없음: " + node));
        }
        List<CodeNodeView> candidates = codeGraphQuery.findNodes(context.projectId(), node, "METHOD", CANDIDATE_LIMIT + 1);
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.isEmpty()) {
            throw new ToolInputException("이름에 '" + node + "'이 들어간 메서드 없음. find_code_nodes로 먼저 찾을 것");
        }
        throw new ToolInputException("후보가 여러 개임. 노드 id로 다시 호출할 것:\n" + format(candidates));
    }

    static String format(List<CodeNodeView> nodes) {
        if (nodes.isEmpty()) {
            return "(없음)";
        }
        return nodes.stream()
                .map(n -> "[" + n.id() + "] " + n.kind() + " " + n.qualifiedName()
                        + (n.filePath() == null ? "" : "  (" + n.filePath() + (n.line() == null ? "" : ":" + n.line()) + ")"))
                .collect(Collectors.joining("\n"));
    }
}
