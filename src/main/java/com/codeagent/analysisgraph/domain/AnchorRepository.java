package com.codeagent.analysisgraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnchorRepository extends JpaRepository<Anchor, Anchor.AnchorId> {

    List<Anchor> findByIdCodeNodeId(Long codeNodeId);

    List<Anchor> findByIdAnalysisNodeId(Long analysisNodeId);
}
