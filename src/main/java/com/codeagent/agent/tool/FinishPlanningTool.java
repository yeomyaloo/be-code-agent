package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.AnalysisGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 계획 담당 전용. 더 만들 할 일이 없다고 판단하면 계획을 끝낸다. 남은 할 일이 끝나면 검증 단계로 넘어간다.
 */
@Component
@RequiredArgsConstructor
public class FinishPlanningTool implements AgentTool {

    public static final String NAME = "finish_planning";

    private final PlanSupport plan;
    private final AnalysisGraphService analysisGraph;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "계획을 끝낸다. 조사할 만한 일을 모두 할 일로 만들었거나, 결과를 보니 후속 조사가 필요 없을 때 호출한다. "
                + "이번 회차에 만든 할 일은 그대로 실행되고, 다음 계획 회차는 열리지 않는다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of("reason", Map.of("type", "string", "description", "계획을 끝내는 이유 (한국어 한두 문장)"));
    }

    @Override
    public List<String> required() {
        return List.of("reason");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        String reason = input.string("reason");
        analysisGraph.updateProps(plan.goal(context).getId(), props -> {
            props.put("planningFinished", true);
            props.put("finishReason", reason);
            props.put("finishedRound", plan.round(context));
        });
        return "계획을 끝냄. 이제 도구를 더 부르지 말고 이번 회차 계획을 한두 문장으로 요약할 것";
    }
}
