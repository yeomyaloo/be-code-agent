package com.codeagent.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    boolean existsByProjectIdAndStatus(Long projectId, JobStatus status);
}
