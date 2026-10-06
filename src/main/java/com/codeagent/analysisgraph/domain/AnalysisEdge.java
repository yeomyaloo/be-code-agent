package com.codeagent.analysisgraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    private Long srcId;

    private Long dstId;

    @Enumerated(EnumType.STRING)
    private AnalysisEdgeKind kind;

    public AnalysisEdge(Long jobId, Long srcId, Long dstId, AnalysisEdgeKind kind) {
        this.jobId = jobId;
        this.srcId = srcId;
        this.dstId = dstId;
        this.kind = kind;
    }
}
