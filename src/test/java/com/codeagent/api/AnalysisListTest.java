package com.codeagent.api;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.api.AnalysisController.JobSummaryView;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트별 분석 목록 API. 로컬 PostgreSQL(codeagent DB)이 필요하다.
 */
@SpringBootTest
class AnalysisListTest {

    @Autowired
    private AnalysisController controller;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private AnalysisGraphService analysisGraph;

    @Test
    void 프로젝트의_분석을_최신순으로_할_일과_발견_개수와_함께_보여준다() {
        Project project = projectRepository.save(new Project("analysis-list-test", "/tmp/analysis-list-test", "java"));
        AnalysisJob older = jobRepository.save(new AnalysisJob(project, 100_000L));
        AnalysisJob newer = jobRepository.save(new AnalysisJob(project, 200_000L));

        AnalysisNode done = analysisGraph.addNode(newer.getId(), AnalysisNodeKind.INTENTION, "할 일 1", "", Map.of());
        analysisGraph.complete(done.getId());
        AnalysisNode failed = analysisGraph.addNode(newer.getId(), AnalysisNodeKind.INTENTION, "할 일 2", "", Map.of());
        analysisGraph.fail(failed.getId());
        analysisGraph.addNode(newer.getId(), AnalysisNodeKind.INTENTION, "할 일 3", "", Map.of());

        AnalysisNode confirmed = analysisGraph.addNode(newer.getId(), AnalysisNodeKind.FINDING, "발견 1", "", Map.of());
        analysisGraph.recordVerdict(newer.getId(), confirmed.getId(), AnalysisNodeStatus.CONFIRMED, Map.of("verdict", "confirmed"));
        AnalysisNode rejected = analysisGraph.addNode(newer.getId(), AnalysisNodeKind.FINDING, "발견 2", "", Map.of());
        analysisGraph.recordVerdict(newer.getId(), rejected.getId(), AnalysisNodeStatus.REJECTED, Map.of("verdict", "rejected"));
        analysisGraph.addNode(newer.getId(), AnalysisNodeKind.FINDING, "발견 3", "", Map.of());
        // 할 일·발견이 아닌 노드는 세지 않는다
        analysisGraph.addNode(newer.getId(), AnalysisNodeKind.FACT, "사실", "", Map.of());

        List<JobSummaryView> list = controller.list(project.getId());

        assertThat(list).extracting(s -> s.job().id()).containsExactly(newer.getId(), older.getId());

        JobSummaryView summary = list.getFirst();
        assertThat(summary.intentions()).isEqualTo(3);
        assertThat(summary.intentionsFinished()).isEqualTo(2);
        assertThat(summary.findings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "OPEN", 1L, "CONFIRMED", 1L, "REJECTED", 1L, "UNCERTAIN", 0L));

        JobSummaryView empty = list.get(1);
        assertThat(empty.intentions()).isZero();
        assertThat(empty.findings().values()).containsOnly(0L);
    }

    @Test
    void 분석이_없으면_빈_목록이다() {
        Project project = projectRepository.save(new Project("analysis-list-test", "/tmp/analysis-list-test", "java"));

        assertThat(controller.list(project.getId())).isEmpty();
    }

    @Test
    void 없는_프로젝트면_404() {
        assertThatThrownBy(() -> controller.list(Long.MAX_VALUE)).isInstanceOf(NoSuchElementException.class);
    }
}
