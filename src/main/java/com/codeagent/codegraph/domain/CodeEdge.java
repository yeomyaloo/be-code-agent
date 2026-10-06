package com.codeagent.codegraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long srcId;

    private Long dstId;

    @Enumerated(EnumType.STRING)
    private CodeEdgeKind kind;

    public CodeEdge(Long projectId, Long srcId, Long dstId, CodeEdgeKind kind) {
        this.projectId = projectId;
        this.srcId = srcId;
        this.dstId = dstId;
        this.kind = kind;
    }
}
