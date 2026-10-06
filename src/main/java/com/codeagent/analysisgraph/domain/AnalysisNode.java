package com.codeagent.analysisgraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    @Enumerated(EnumType.STRING)
    private AnalysisNodeKind kind;

    @Enumerated(EnumType.STRING)
    private AnalysisNodeStatus status;

    private String title;

    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    private String props;

    private String workerId;

    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private AnalysisNode(Long jobId, AnalysisNodeKind kind, String title, String body, String props) {
        this.jobId = jobId;
        this.kind = kind;
        this.status = AnalysisNodeStatus.OPEN;
        this.title = title;
        this.body = body;
        this.props = props == null ? "{}" : props;
        this.createdAt = OffsetDateTime.now();
    }

    public void complete() {
        this.status = AnalysisNodeStatus.DONE;
    }

    public void fail() {
        this.status = AnalysisNodeStatus.FAILED;
    }
}
