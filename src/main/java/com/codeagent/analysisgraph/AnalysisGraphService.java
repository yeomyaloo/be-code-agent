package com.codeagent.analysisgraph;

import com.codeagent.analysisgraph.domain.AnalysisEdge;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisEdgeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.Anchor;
import com.codeagent.analysisgraph.domain.AnchorRepository;
import com.codeagent.analysisgraph.domain.AnchorRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 분석 그래프(목표·할 일·사실·발견)에 노드·연결선·연결점을 기록한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisGraphService {

    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisEdgeRepository edgeRepository;
    private final AnchorRepository anchorRepository;
    private final JsonMapper jsonMapper;

    @Transactional
    public AnalysisNode addNode(Long jobId, AnalysisNodeKind kind, String title, String body, Map<String, Object> props) {
        return nodeRepository.save(AnalysisNode.builder()
                .jobId(jobId)
                .kind(kind)
                .title(title)
                .body(body)
                .props(jsonMapper.writeValueAsString(props))
                .build());
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

    /** 발견을 증명하는 사실이 같은 작업에 있는지 확인한다 */
    public void requireNodeInJob(Long jobId, Long nodeId, AnalysisNodeKind kind) {
        AnalysisNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NoSuchElementException("분석 노드 없음: " + nodeId));
        if (!node.getJobId().equals(jobId) || node.getKind() != kind) {
            throw new NoSuchElementException("이 작업의 " + kind + " 노드가 아님: " + nodeId);
        }
    }

    @Transactional
    public void complete(Long nodeId) {
        nodeRepository.findById(nodeId).ifPresent(AnalysisNode::complete);
    }

    @Transactional
    public void fail(Long nodeId) {
        nodeRepository.findById(nodeId).ifPresent(AnalysisNode::fail);
    }
}
