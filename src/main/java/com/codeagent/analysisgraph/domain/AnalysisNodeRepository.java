package com.codeagent.analysisgraph.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import java.util.Optional;

public interface AnalysisNodeRepository extends JpaRepository<AnalysisNode, Long> {

    /**
     * 선행 할 일(DEPENDS_ON)이 모두 끝난 OPEN 상태의 할 일 하나를 CLAIMED로 바꾸고 돌려준다.
     * SKIP LOCKED 덕분에 여러 실행 담당이 동시에 호출해도 같은 할 일을 중복으로 가져가지 않는다.
     */
    @Transactional
    @Query(nativeQuery = true, value = """
            update analysis_node set status = 'CLAIMED', worker_id = :workerId
            where id = (
                select n.id from analysis_node n
                where n.job_id = :jobId and n.kind = 'INTENTION' and n.status = 'OPEN'
                  and not exists (
                      select 1 from analysis_edge e
                      join analysis_node d on d.id = e.dst_id
                      where e.src_id = n.id and e.kind = 'DEPENDS_ON' and d.status <> 'DONE')
                order by n.id
                for update skip locked
                limit 1)
            returning *
            """)
    Optional<AnalysisNode> claimNextIntention(Long jobId, String workerId);

    List<AnalysisNode> findByJobIdOrderById(Long jobId);
}
