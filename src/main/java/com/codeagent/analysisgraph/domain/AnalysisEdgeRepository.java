package com.codeagent.analysisgraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisEdgeRepository extends JpaRepository<AnalysisEdge, Long> {

    List<AnalysisEdge> findByDstIdAndKind(Long dstId, AnalysisEdgeKind kind);
}
