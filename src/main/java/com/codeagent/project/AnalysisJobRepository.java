package com.codeagent.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    boolean existsByProjectIdAndStatus(Long projectId, JobStatus status);

    /** 프로젝트의 분석 작업, 최신순 */
    List<AnalysisJob> findByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 토큰 사용량을 DB에서 바로 더한다.
     * 여러 실행 담당이 동시에 기록하므로 엔티티를 읽어 고쳐 쓰면 서로 덮어쓴다.
     */
    @Modifying
    @Transactional
    @Query("""
            update AnalysisJob j
            set j.inputTokens = j.inputTokens + :inputTokens,
                j.outputTokens = j.outputTokens + :outputTokens,
                j.usedTokens = j.usedTokens + :inputTokens + :outputTokens
            where j.id = :jobId
            """)
    void addUsage(Long jobId, long inputTokens, long outputTokens);

    /** 작업을 끝낸다. 사용량 칸은 건드리지 않는다 */
    @Modifying
    @Transactional
    @Query("update AnalysisJob j set j.status = :status, j.error = :error, j.finishedAt = :finishedAt where j.id = :jobId")
    void finish(Long jobId, JobStatus status, String error, OffsetDateTime finishedAt);
}
