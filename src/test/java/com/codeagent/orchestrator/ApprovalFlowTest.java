package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.AgentLoop.Outcome;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.ToolInput;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.codegraph.ingest.CodeGraphIndexer;
import com.codeagent.llm.ModelTier;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.JobStatus;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 사람 승인 단계. 계획이 나오면 Worker 를 돌리기 전에 멈추고, 승인(일부 할 일 빼기)·거절에 따라 이어가거나 멈춘다.
 * 로컬 PostgreSQL 이 필요하다.
 */
@SpringBootTest
class ApprovalFlowTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app").toAbsolutePath();

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private CodeGraphIndexer codeGraphIndexer;
    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private AnalysisNodeRepository nodeRepository;
    @Autowired
    private AnalysisGraphService analysisGraph;

    @MockitoBean
    private AgentLoop agentLoop;

    @Test
    void 회차마다_승인을_기다리고_뺀_할_일은_실행하지_않는다() throws InterruptedException {
        Project project = indexedProject();
        AtomicInteger plannerRounds = new AtomicInteger();
        List<String> workedOn = Collections.synchronizedList(new java.util.ArrayList<>());
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            if (request.tier() == ModelTier.PLANNER) {
                if (plannerRounds.incrementAndGet() == 1) {
                    call(request, "add_intention", Map.of("title", "A 조사", "instructions", "A"));
                    String b = call(request, "add_intention", Map.of("title", "B 조사", "instructions", "B"));
                    call(request, "add_intention", Map.of("title", "B 다음 조사", "instructions", "C", "depends_on", List.of(id(b))));
                } else {
                    assertThat(call(request, "get_plan", Map.of())).contains("[사람이 뺌] B 조사", "[완료] A 조사");
                    call(request, "add_intention", Map.of("title", "후속 조사", "instructions", "D"));
                    call(request, "finish_planning", Map.of("reason", "끝"));
                }
                return new AgentRun(Outcome.COMPLETED, "계획함", 2, 10, 5, 0, null);
            }
            workedOn.add(nodeRepository.findById(request.context().intentionId()).orElseThrow().getTitle());
            return new AgentRun(Outcome.COMPLETED, "판정: 취약하지 않음", 1, 10, 5, 0, null);
        });

        AnalysisJob job = analysisService.start(project.getId(), 10, 1_000_000, PlannerMode.LLM, true);

        // 1회차 계획 뒤 승인 대기. Worker 는 아직 돌지 않는다
        waitForStatus(job.getId(), JobStatus.WAITING_APPROVAL);
        assertThat(workedOn).isEmpty();
        AnalysisNode b = intention(job.getId(), "B 조사");
        assertThatThrownBy(() -> analysisService.approve(job.getId(), List.of(999_999L), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("대기 중인 할 일이 아님");

        analysisService.approve(job.getId(), List.of(b.getId()), "B 는 범위 밖");
        assertThatThrownBy(() -> analysisService.approve(job.getId(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("승인 대기 중인 작업이 아님");

        // 2회차 계획에서 후속 할 일을 더했으므로 다시 승인 대기
        waitForStatus(job.getId(), JobStatus.WAITING_APPROVAL);
        assertThat(workedOn).containsExactly("A 조사");
        assertThat(intention(job.getId(), "B 조사").getStatus()).isEqualTo(AnalysisNodeStatus.SKIPPED);
        // 뺀 할 일에 의존하던 할 일은 실패로 정리된다
        assertThat(intention(job.getId(), "B 다음 조사").getStatus()).isEqualTo(AnalysisNodeStatus.FAILED);

        analysisService.approve(job.getId(), List.of(), null);
        AnalysisJob finished = waitForStatus(job.getId(), JobStatus.DONE);

        assertThat(workedOn).containsExactly("A 조사", "후속 조사");
        assertThat(plannerRounds).hasValue(2);
        Map<String, Object> goal = analysisGraph.props(goal(job.getId()));
        assertThat(goal).containsEntry("approvedRound", 2);
        assertThat((List<?>) goal.get("approvals")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) goal.get("approvals")).get(0);
        assertThat(first).containsEntry("decision", "APPROVED")
                .containsEntry("comment", "B 는 범위 밖").containsEntry("round", 1);
        assertThat(finished.getError()).isNull();
    }

    @Test
    void 거절하면_작업을_멈추고_남은_할_일을_건너뛴다() throws InterruptedException {
        Project project = indexedProject();
        AtomicInteger workers = new AtomicInteger();
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            workers.incrementAndGet();
            return new AgentRun(Outcome.COMPLETED, "", 1, 1, 1, 0, null);
        });

        // 경로 계획도 처음 만든 할 일을 승인받는다
        AnalysisJob job = analysisService.start(project.getId(), 3, 1_000_000, PlannerMode.PATHS, true);
        waitForStatus(job.getId(), JobStatus.WAITING_APPROVAL);

        analysisService.reject(job.getId(), "비용이 큼");

        AnalysisJob stopped = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(stopped.getError()).isEqualTo("사람이 계획을 거절함: 비용이 큼");
        assertThat(workers).hasValue(0);
        assertThat(nodeRepository.findByJobIdOrderById(job.getId()).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.INTENTION))
                .hasSize(3).allMatch(n -> n.getStatus() == AnalysisNodeStatus.SKIPPED);
        assertThatThrownBy(() -> analysisService.reject(job.getId(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인을_요구하지_않으면_멈추지_않는다() throws InterruptedException {
        Project project = indexedProject();
        when(agentLoop.run(any())).thenReturn(new AgentRun(Outcome.COMPLETED, "", 1, 1, 1, 0, null));

        AnalysisJob job = analysisService.start(project.getId(), 2, 1_000_000, PlannerMode.PATHS, false);

        assertThat(waitForStatus(job.getId(), JobStatus.DONE).getStatus()).isEqualTo(JobStatus.DONE);
    }

    private Project indexedProject() {
        Project project = projectRepository.save(new Project("approval-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());
        return project;
    }

    private AnalysisNode intention(Long jobId, String title) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.INTENTION && n.getTitle().equals(title))
                .findFirst().orElseThrow();
    }

    private AnalysisNode goal(Long jobId) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.GOAL).findFirst().orElseThrow();
    }

    private static long id(String addResult) {
        Matcher m = Pattern.compile("할 일 #(\\d+)").matcher(addResult);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static String call(AgentRequest request, String toolName, Map<String, Object> input) {
        AgentTool tool = request.tools().stream().filter(t -> t.name().equals(toolName)).findFirst().orElseThrow();
        return tool.execute(new ToolInput(input), request.context());
    }

    private AnalysisJob waitForStatus(Long jobId, JobStatus status) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == status) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("작업이 " + status + " 가 되지 않음: " + jobRepository.findById(jobId).orElseThrow().getStatus());
    }
}
