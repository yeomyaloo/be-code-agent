package com.codeagent.project;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    private Long budgetTokens;

    private long usedTokens;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    public AnalysisJob(Project project, Long budgetTokens) {
        this.project = project;
        this.budgetTokens = budgetTokens;
        this.status = JobStatus.PENDING;
    }
}
