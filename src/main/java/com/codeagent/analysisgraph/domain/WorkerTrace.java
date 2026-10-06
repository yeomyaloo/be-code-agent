package com.codeagent.analysisgraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkerTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    private Long intentionId;

    private String workerId;

    private int step;

    private String role;

    private String toolName;

    private String content;

    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private WorkerTrace(Long jobId, Long intentionId, String workerId, int step,
                        String role, String toolName, String content) {
        this.jobId = jobId;
        this.intentionId = intentionId;
        this.workerId = workerId;
        this.step = step;
        this.role = role;
        this.toolName = toolName;
        this.content = content;
        this.createdAt = OffsetDateTime.now();
    }
}
