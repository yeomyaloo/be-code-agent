package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 검증 담당 전용. 검증 대상 발견(ToolContext.intentionId)에 판정을 기록한다.
 */
@Component
@RequiredArgsConstructor
public class SubmitVerdictTool implements AgentTool {

    public static final String NAME = "submit_verdict";

    private static final Map<String, AnalysisNodeStatus> VERDICTS = Map.of(
            "confirmed", AnalysisNodeStatus.CONFIRMED,
            "rejected", AnalysisNodeStatus.REJECTED,
            "uncertain", AnalysisNodeStatus.UNCERTAIN);
    private static final Set<String> SEVERITIES = Set.of("critical", "high", "medium", "low", "info");
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");

    private final AnalysisGraphService analysisGraph;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "검증 대상 발견에 대한 최종 판정을 제출한다. 조사를 마친 뒤 한 번만 호출한다. "
                + "confirmed: 외부 입력으로 실제 공격 가능함을 코드로 확인함. "
                + "rejected: 공격을 막는 장치가 있거나 입력이 닿지 않아 공격 불가능함을 확인함 (오탐). "
                + "uncertain: 확인에 필요한 코드가 저장소에 없는 등 어느 쪽으로도 판단할 근거가 부족함.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("verdict", Map.of("type", "string", "enum", List.copyOf(VERDICTS.keySet())));
        props.put("reasoning", Map.of("type", "string", "description", "판정 근거 (한국어, 파일:줄 포함)"));
        props.put("blocking_controls", Map.of("type", "string",
                "description", "확인한 방어 장치(입력 검증, 파라미터 바인딩, 권한 검사 등)와 그것이 공격을 막는지 여부"));
        props.put("severity", Map.of("type", "string", "enum", List.copyOf(SEVERITIES),
                "description", "검증 후 판단한 심각도 (confirmed일 때)"));
        props.put("confidence", Map.of("type", "string", "enum", List.copyOf(CONFIDENCES)));
        return props;
    }

    @Override
    public List<String> required() {
        return List.of("verdict", "reasoning", "confidence");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        String verdict = input.oneOf("verdict", VERDICTS.keySet());
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("verdict", verdict);
        verification.put("reasoning", input.string("reasoning"));
        verification.put("blockingControls", input.optionalString("blocking_controls").orElse(null));
        verification.put("severity", input.optionalString("severity").map(s -> {
            if (!SEVERITIES.contains(s)) {
                throw new ToolInputException("severity는 " + SEVERITIES + " 중 하나여야 함");
            }
            return s;
        }).orElse(null));
        verification.put("confidence", input.oneOf("confidence", CONFIDENCES));
        verification.put("verifier", context.workerId());

        boolean recorded = analysisGraph.recordVerdict(context.jobId(), context.intentionId(), VERDICTS.get(verdict), verification);
        if (!recorded) {
            throw new ToolInputException("이 발견은 이미 판정됨. 더 할 일이 없으면 짧게 마무리할 것");
        }
        return "판정 기록함: " + verdict + ". 이제 도구를 더 부르지 말고 한두 문장으로 마무리할 것";
    }
}
