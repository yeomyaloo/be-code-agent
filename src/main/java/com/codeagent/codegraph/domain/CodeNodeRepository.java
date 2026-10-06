package com.codeagent.codegraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CodeNodeRepository extends JpaRepository<CodeNode, Long> {

    Optional<CodeNode> findByProjectIdAndKindAndQualifiedName(Long projectId, CodeNodeKind kind, String qualifiedName);

    List<CodeNode> findByProjectIdAndKind(Long projectId, CodeNodeKind kind);
}
