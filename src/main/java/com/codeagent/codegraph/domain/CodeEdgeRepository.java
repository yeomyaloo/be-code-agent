package com.codeagent.codegraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeEdgeRepository extends JpaRepository<CodeEdge, Long> {

    List<CodeEdge> findBySrcIdAndKind(Long srcId, CodeEdgeKind kind);

    List<CodeEdge> findByDstIdAndKind(Long dstId, CodeEdgeKind kind);
}
