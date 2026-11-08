package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 계획 담당 전용. 지금까지의 할 일, 실행 담당의 결론, 발견을 한눈에 본다.
 */
@Component
@RequiredArgsConstructor
public class GetPlanTool implements AgentTool {

    public static final String NAME = "get_plan";

    private final PlanSupport plan;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "공유 할 일 목록을 본다: 할 일마다 상태(대기/조사 중/완료/실패), 만든 회차, 선행 할 일, 실행 담당의 결론 요약, "
                + "그리고 지금까지 기록된 발견. 2회차부터는 먼저 이걸로 결과를 확인하고 후속 할 일이 필요한지 판단한다.";
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
        List<AnalysisNode> intentions = plan.nodes(context.jobId(), AnalysisNodeKind.INTENTION);
        Map<Long, AnalysisNode> conclusions = plan.conclusions(context.jobId());
        int max = plan.maxIntentions(context);

        StringBuilder sb = new StringBuilder();
        sb.append("계획 ").append(plan.round(context)).append("회차 · 할 일 ").append(intentions.size())
                .append(" / 상한 ").append(max).append("개 (남은 자리 ").append(Math.max(0, max - intentions.size())).append(")\n\n");
        if (intentions.isEmpty()) {
            sb.append("할 일 없음\n");
        }
        for (AnalysisNode intention : intentions) {
            Map<String, Object> props = plan.props(intention);
            sb.append("#").append(intention.getId()).append(" [").append(label(intention)).append("] ")
                    .append(intention.getTitle());
            Object round = props.get("plannedRound");
            if (round != null) {
                sb.append(" (").append(round).append("회차)");
            }
            Object deps = props.get("dependsOn");
            if (deps instanceof List<?> list && !list.isEmpty()) {
                sb.append(" · 선행 ").append(list);
            }
            sb.append('\n');
            AnalysisNode conclusion = conclusions.get(intention.getId());
            if (conclusion != null && conclusion.getBody() != null) {
                sb.append("  결론: ").append(PlanSupport.shorten(conclusion.getBody(), 400)).append('\n');
            }
        }

        List<AnalysisNode> findings = plan.nodes(context.jobId(), AnalysisNodeKind.FINDING);
        sb.append("\n발견 ").append(findings.size()).append("개\n");
        for (AnalysisNode finding : findings) {
            Map<String, Object> props = plan.props(finding);
            sb.append("- 발견 #").append(finding.getId()).append(" [").append(props.getOrDefault("cwe", "-")).append(' ')
                    .append(props.getOrDefault("severity", "-")).append("] ").append(finding.getTitle()).append('\n');
        }
        return sb.toString();
    }

    private static String label(AnalysisNode intention) {
        return switch (intention.getStatus()) {
            case OPEN -> "대기";
            case CLAIMED -> "조사 중";
            case DONE -> "완료";
            case FAILED -> "실패";
            case SKIPPED -> "사람이 뺌";
            default -> intention.getStatus().name();
        };
    }
}
