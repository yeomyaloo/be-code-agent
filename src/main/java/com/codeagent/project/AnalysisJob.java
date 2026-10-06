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

    private long inputTokens;

    private long outputTokens;

    private String error;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    public AnalysisJob(Project project, Long budgetTokens) {
        this.project = project;
        this.budgetTokens = budgetTokens;
        this.status = JobStatus.PENDING;
    }

    public void start() {
        this.status = JobStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    /** 끝난 작업을 다시 진행 상태로 (검증 재실행 등) */
    public void resume() {
        this.status = JobStatus.RUNNING;
        this.error = null;
        this.finishedAt = null;
    }

    public void finish(JobStatus status, String error) {
        this.status = status;
        this.error = error;
        this.finishedAt = OffsetDateTime.now();
    }

    public void addUsage(long inputTokens, long outputTokens) {
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
        this.usedTokens = this.inputTokens + this.outputTokens;
    }

    public long remainingTokens() {
        return budgetTokens == null ? Long.MAX_VALUE : Math.max(0, budgetTokens - usedTokens);
    }
}
