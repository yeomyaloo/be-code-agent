package com.codeagent.analysisgraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkerTraceRepository extends JpaRepository<WorkerTrace, Long> {

    @Query(nativeQuery = true, value = """
            select * from worker_trace
            where job_id = :jobId
              and to_tsvector('simple', coalesce(content, '')) @@ plainto_tsquery('simple', :q)
            order by id desc
            limit :limit
            """)
    List<WorkerTrace> search(Long jobId, String q, int limit);

    List<WorkerTrace> findByJobIdOrderById(Long jobId);

    List<WorkerTrace> findByJobIdAndIntentionIdOrderById(Long jobId, Long intentionId);
}
