package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 계획 담당 도구들이 함께 쓰는 조회. 계획 담당의 ToolContext.intentionId 는 목표(GOAL) 노드 id 다.
 *
 * <p>목표 props: maxIntentions(할 일 상한), plannerRound(지금 회차), planningFinished, finishReason
 */
@Component
@RequiredArgsConstructor
public class PlanSupport {

    public static final int PATH_DEPTH = 12;

    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;

    AnalysisNode goal(ToolContext context) {
        AnalysisNode goal = nodeRepository.findById(context.intentionId())
                .orElseThrow(() -> new ToolInputException("목표 노드 없음: " + context.intentionId()));
        if (goal.getKind() != AnalysisNodeKind.GOAL || !goal.getJobId().equals(context.jobId())) {
            throw new IllegalStateException("계획 담당 도구는 목표(GOAL) 노드 맥락에서만 쓸 수 있음");
        }
        return goal;
    }

    Map<String, Object> goalProps(ToolContext context) {
        return analysisGraph.props(goal(context));
    }

    int maxIntentions(ToolContext context) {
        return ((Number) goalProps(context).getOrDefault("maxIntentions", 10)).intValue();
    }

    int round(ToolContext context) {
        return ((Number) goalProps(context).getOrDefault("plannerRound", 1)).intValue();
    }

    List<AnalysisNode> nodes(Long jobId, AnalysisNodeKind kind) {
        return nodeRepository.findByJobIdOrderById(jobId).stream().filter(n -> n.getKind() == kind).toList();
    }

    /** 실행 담당이 할 일마다 남기는 결론 FACT ("할 일 #{id} 결론") */
    Map<Long, AnalysisNode> conclusions(Long jobId) {
        Map<Long, AnalysisNode> byIntention = new LinkedHashMap<>();
        for (AnalysisNode fact : nodes(jobId, AnalysisNodeKind.FACT)) {
            String title = fact.getTitle();
            if (title.startsWith("할 일 #") && title.endsWith(" 결론")) {
                try {
                    byIntention.put(Long.parseLong(title.substring("할 일 #".length(), title.length() - " 결론".length())), fact);
                } catch (NumberFormatException ignored) {
                    // 다른 형식의 사실
                }
            }
        }
        return byIntention;
    }

    /** 같은 (진입점, 위험 지점) 쌍은 가장 짧은 경로 하나만. sinkPaths 는 짧은 순으로 온다 */
    List<SinkPathView> shortestPaths(Long projectId) {
        Map<String, SinkPathView> byPair = new LinkedHashMap<>();
        for (SinkPathView path : codeGraphQuery.sinkPaths(projectId, PATH_DEPTH)) {
            byPair.putIfAbsent(path.entryPoint().id() + "->" + path.sink().id(), path);
        }
        return List.copyOf(byPair.values());
    }

    Optional<SinkPathView> path(Long projectId, Long entryId, Long sinkId) {
        return shortestPaths(projectId).stream()
                .filter(p -> p.entryPoint().id().equals(entryId) && p.sink().id().equals(sinkId))
                .findFirst();
    }

    /** 이 (진입점, 위험 지점) 쌍을 이미 다루는 할 일 */
    Optional<AnalysisNode> intentionFor(Long jobId, Long entryId, Long sinkId) {
        return nodes(jobId, AnalysisNodeKind.INTENTION).stream()
                .filter(n -> {
                    Map<String, Object> props = analysisGraph.props(n);
                    return Objects.equals(asLong(props.get("entryNodeId")), entryId) && Objects.equals(asLong(props.get("sinkNodeId")), sinkId);
                })
                .findFirst();
    }

    Map<String, Object> props(AnalysisNode node) {
        return analysisGraph.props(node);
    }

    static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\\s*\\n\\s*", " / ");
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
