package com.codeagent.analysisgraph;

import com.codeagent.analysisgraph.domain.AnalysisEdge;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisEdgeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.analysisgraph.domain.Anchor;
import com.codeagent.analysisgraph.domain.AnchorRepository;
import com.codeagent.analysisgraph.domain.AnchorRole;
import com.codeagent.analysisgraph.domain.ReviewDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * 분석 그래프(목표·할 일·사실·발견)에 노드·연결선·연결점을 기록한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisGraphService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisEdgeRepository edgeRepository;
    private final AnchorRepository anchorRepository;
    private final JsonMapper jsonMapper;
    private final AnalysisEvents events;

    @Transactional
    public AnalysisNode addNode(Long jobId, AnalysisNodeKind kind, String title, String body, Map<String, Object> props) {
        AnalysisNode node = nodeRepository.save(AnalysisNode.builder()
                .jobId(jobId)
                .kind(kind)
                .title(title)
                .body(body)
                .props(jsonMapper.writeValueAsString(props))
                .build());
        events.changed(jobId, "node");
        return node;
    }

    @Transactional
    public void addEdge(Long jobId, Long srcId, Long dstId, AnalysisEdgeKind kind) {
        edgeRepository.save(new AnalysisEdge(jobId, srcId, dstId, kind));
    }

    @Transactional
    public void anchor(Long analysisNodeId, Collection<Long> codeNodeIds, AnchorRole role) {
        codeNodeIds.stream()
                .distinct()
                .map(codeNodeId -> new Anchor(analysisNodeId, codeNodeId, role))
                .forEach(anchorRepository::save);
    }

    /**
     * 목표 아래에 할 일을 만든다 (GOAL ─SPAWNS→ INTENTION, 선행 할 일은 INTENTION ─DEPENDS_ON→ 선행).
     *
     * @param sourceId    진입점 코드 노드 (없으면 null)
     * @param sinkId      위험 지점 코드 노드 (없으면 null)
     * @param examinedIds 호출 경로상의 메서드 노드
     * @param dependsOn   먼저 끝나야 하는 할 일 id
     */
    @Transactional
    public AnalysisNode addIntention(Long jobId, Long goalId, String title, String body, Map<String, Object> props,
                                     Long sourceId, Long sinkId, Collection<Long> examinedIds, Collection<Long> dependsOn) {
        AnalysisNode intention = addNode(jobId, AnalysisNodeKind.INTENTION, title, body, props);
        addEdge(jobId, goalId, intention.getId(), AnalysisEdgeKind.SPAWNS);
        dependsOn.forEach(dep -> addEdge(jobId, intention.getId(), dep, AnalysisEdgeKind.DEPENDS_ON));
        if (sourceId != null) {
            anchor(intention.getId(), List.of(sourceId), AnchorRole.SOURCE);
        }
        if (sinkId != null) {
            anchor(intention.getId(), List.of(sinkId), AnchorRole.SINK);
        }
        anchor(intention.getId(), examinedIds, AnchorRole.EXAMINED);
        return intention;
    }

    /** 발견을 증명하는 사실이 같은 작업에 있는지 확인한다 */
    public void requireNodeInJob(Long jobId, Long nodeId, AnalysisNodeKind kind) {
        AnalysisNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NoSuchElementException("분석 노드 없음: " + nodeId));
        if (!node.getJobId().equals(jobId) || node.getKind() != kind) {
            throw new NoSuchElementException("이 작업의 " + kind + " 노드가 아님: " + nodeId);
        }
    }

    /**
     * 발견에 검증 결과를 기록한다. props.verification 에 판정 내용을 넣고 상태를 바꾼다.
     *
     * @return 이미 판정된 발견이면 false
     */
    @Transactional
    public boolean recordVerdict(Long jobId, Long findingId, AnalysisNodeStatus status, Map<String, Object> verification) {
        AnalysisNode finding = lockFinding(jobId, findingId);
        if (finding.getStatus() != AnalysisNodeStatus.OPEN) {
            return false;
        }
        Map<String, Object> props = new LinkedHashMap<>(jsonMapper.readValue(finding.getProps(), JSON_OBJECT));
        props.put("verification", verification);
        finding.applyVerdict(status, jsonMapper.writeValueAsString(props));
        events.changed(jobId, "verdict");
        return true;
    }

    /**
     * 사람이 발견을 검토한 결과를 props.review 에 기록한다. 검증 담당의 판정(status)은 그대로 둔다.
     */
    @Transactional
    public AnalysisNode recordReview(Long jobId, Long findingId, ReviewDecision decision, String comment) {
        AnalysisNode finding = lockFinding(jobId, findingId);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("decision", decision.name());
        review.put("comment", comment);
        review.put("reviewedAt", OffsetDateTime.now().toString());
        Map<String, Object> props = new LinkedHashMap<>(jsonMapper.readValue(finding.getProps(), JSON_OBJECT));
        props.put("review", review);
        finding.updateProps(jsonMapper.writeValueAsString(props));
        events.changed(jobId, "review");
        return finding;
    }

    private AnalysisNode lockFinding(Long jobId, Long findingId) {
        AnalysisNode finding = nodeRepository.findForUpdate(findingId)
                .orElseThrow(() -> new NoSuchElementException("분석 노드 없음: " + findingId));
        if (!finding.getJobId().equals(jobId) || finding.getKind() != AnalysisNodeKind.FINDING) {
            throw new NoSuchElementException("이 작업의 FINDING 노드가 아님: " + findingId);
        }
        return finding;
    }

    public Map<String, Object> props(AnalysisNode node) {
        return jsonMapper.readValue(node.getProps(), JSON_OBJECT);
    }

    /** 노드를 잠그고 props 를 고쳐 쓴다 (계획 담당의 회차·종료 표시 등) */
    @Transactional
    public Map<String, Object> updateProps(Long nodeId, Consumer<Map<String, Object>> change) {
        AnalysisNode node = nodeRepository.findForUpdate(nodeId)
                .orElseThrow(() -> new NoSuchElementException("분석 노드 없음: " + nodeId));
        Map<String, Object> props = new LinkedHashMap<>(props(node));
        change.accept(props);
        node.updateProps(jsonMapper.writeValueAsString(props));
        events.changed(node.getJobId(), "node");
        return props;
    }

    /**
     * 선행 할 일이 실패해서 영영 시작할 수 없는 할 일을 실패로 바꾼다. 연쇄로 막힌 것까지 모두 처리한다.
     *
     * @return 실패로 바꾼 할 일 수
     */
    @Transactional
    public int failBlockedIntentions(Long jobId) {
        int total = 0;
        for (int changed = nodeRepository.failBlockedIntentions(jobId); changed > 0; changed = nodeRepository.failBlockedIntentions(jobId)) {
            total += changed;
        }
        if (total > 0) {
            events.changed(jobId, "node");
        }
        return total;
    }

    @Transactional
    public void complete(Long nodeId) {
        nodeRepository.findById(nodeId).ifPresent(node -> {
            node.complete();
            events.changed(node.getJobId(), "node");
        });
    }

    @Transactional
    public void fail(Long nodeId) {
        nodeRepository.findById(nodeId).ifPresent(node -> {
            node.fail();
            events.changed(node.getJobId(), "node");
        });
    }
}
