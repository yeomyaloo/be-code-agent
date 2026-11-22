package com.codeagent.dependency;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DependencyScanRepository extends JpaRepository<DependencyScan, Long> {

    /** 최근 검사부터 */
    List<DependencyScan> findByProjectIdOrderByIdDesc(Long projectId, Limit limit);

    /** 이 검사 바로 앞의 검사 */
    Optional<DependencyScan> findFirstByProjectIdAndIdLessThanOrderByIdDesc(Long projectId, Long id);
}
