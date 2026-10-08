package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.AgentLoop.Outcome;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.ToolInput;
import com.codeagent.agent.tool.ToolInputException;
import com.codeagent.llm.ModelTier;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.codegraph.ingest.CodeGraphIndexer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실제 LLM 대신 가짜 AgentLoop가 도구를 직접 호출해서, 할 일 생성 → 배분 → 도구 기록 → 작업 종료 흐름을 확인한다.
 * 로컬 PostgreSQL(codeagent DB)이 필요하다.
 */
@SpringBootTest
class AnalysisFlowTest {

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

    @MockitoBean
    private AgentLoop agentLoop;

    @Test
    void 실행_담당이_발견을_기록하고_검증_담당이_판정한다() throws InterruptedException {
        Project project = projectRepository.save(new Project("analysis-flow-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());

        AtomicInteger runs = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            if (request.tier() == ModelTier.VERIFIER) {
                return verify(request, verifications.incrementAndGet());
            }
            runs.incrementAndGet();
            assertThat(toolNames(request)).doesNotContain("submit_verdict");

            String nodes = call(request, "find_code_nodes", Map.of("query", "UserServiceImpl#findByName"));
            assertThat(nodes).contains("METHOD com.example.vuln.service.UserServiceImpl#findByName(String)");
            assertThat(call(request, "get_callers", Map.of("node", "UserServiceImpl#findByName")))
                    .contains("UserServiceImpl#search(String)");
            assertThat(call(request, "get_callees", Map.of("node", "UserServiceImpl#findByName")))
                    .contains("SINK");
            assertThatThrownBy(() -> call(request, "record_finding", Map.of("title", "x")))
                    .isInstanceOf(ToolInputException.class);

            String fact = call(request, "record_fact", Map.of("title", "입력이 그대로 쓰임", "detail", "UserServiceImpl.java:24"));
            long factId = Long.parseLong(fact.replaceAll("\\D+", " ").trim().split(" ")[0]);
            String finding = call(request, "record_finding", Map.of(
                    "title", "테스트 발견",
                    "cwe", "CWE-89",
                    "severity", "high",
                    "confidence", "high",
                    "description", "설명",
                    "evidence", List.of(Map.of("file", "UserServiceImpl.java", "line", 25, "note", "쿼리 실행")),
                    "fact_ids", List.of(factId)));
            assertThat(finding).contains("검증 대기");
            assertThat(call(request, "search_worker_traces", Map.of("query", "UserServiceImpl"))).isNotBlank();

            return new AgentRun(Outcome.COMPLETED, "판정: 취약", 3, 100, 50, 0, null);
        });

        AnalysisJob job = analysisService.start(project.getId(), 10, 1_000_000);
        AnalysisJob finished = waitUntilFinished(job.getId());

        // 픽스처의 (진입점, 위험 지점) 쌍: search→SQL, ping→exec, files GET/HEAD × (readAllBytes, Paths.get)
        assertThat(runs.get()).isEqualTo(6);
        assertThat(verifications.get()).isEqualTo(6);
        assertThat(finished.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(finished.getUsedTokens()).as("실행 담당 6회 + 검증 담당 6회").isEqualTo(12 * 150);

        List<AnalysisNode> nodes = nodeRepository.findByJobIdOrderById(job.getId());
        assertThat(nodes).filteredOn(n -> n.getKind() == AnalysisNodeKind.INTENTION)
                .hasSize(6)
                .allMatch(n -> n.getStatus() == AnalysisNodeStatus.DONE);
        // 검증 1회차는 판정 없이 끝남(UNCERTAIN), 짝수 회차는 confirmed, 나머지는 rejected
        assertThat(nodes).filteredOn(n -> n.getKind() == AnalysisNodeKind.FINDING)
                .extracting(AnalysisNode::getStatus)
                .containsExactlyInAnyOrder(
                        AnalysisNodeStatus.UNCERTAIN,
                        AnalysisNodeStatus.CONFIRMED, AnalysisNodeStatus.CONFIRMED, AnalysisNodeStatus.CONFIRMED,
                        AnalysisNodeStatus.REJECTED, AnalysisNodeStatus.REJECTED);
        assertThat(nodes).filteredOn(n -> n.getStatus() == AnalysisNodeStatus.CONFIRMED)
                .allMatch(n -> n.getProps().contains("\"verification\"") && n.getProps().contains("\"verifier-1\""));
        assertThat(nodes).filteredOn(n -> n.getKind() == AnalysisNodeKind.GOAL).hasSize(1);

        // 검증 재실행: 검증 대기 발견이 없으므로 검증 담당을 다시 부르지 않고 끝난다
        analysisService.verify(job.getId());
        assertThat(waitUntilFinished(job.getId()).getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(verifications.get()).isEqualTo(6);
        assertThatThrownBy(() -> analysisService.verify(-1L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void 실행_담당_여러_개가_동시에_할_일을_처리한다() throws InterruptedException {
        Project project = projectRepository.save(new Project("parallel-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());

        // 처음 세 번의 호출이 동시에 진행 중이어야 장벽을 넘는다. 병렬이 아니면 시간 초과로 작업이 실패한다
        CyclicBarrier barrier = new CyclicBarrier(3);
        AtomicInteger calls = new AtomicInteger();
        Set<String> workerIds = ConcurrentHashMap.newKeySet();
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            workerIds.add(request.context().workerId());
            if (calls.incrementAndGet() <= 3) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return new AgentRun(Outcome.COMPLETED, "판정: 취약하지 않음", 1, 10, 5, 0, null);
        });

        AnalysisJob job = analysisService.start(project.getId(), 6, 1_000_000);
        AnalysisJob finished = waitUntilFinished(job.getId());

        assertThat(finished.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(workerIds).containsExactlyInAnyOrder("worker-1", "worker-2", "worker-3");
        assertThat(calls.get()).isEqualTo(6);
        assertThat(finished.getUsedTokens()).as("동시에 기록해도 사용량이 빠지지 않는다").isEqualTo(6 * 15);
        assertThat(nodeRepository.findByJobIdOrderById(job.getId()))
                .filteredOn(n -> n.getKind() == AnalysisNodeKind.INTENTION)
                .allMatch(n -> n.getStatus() == AnalysisNodeStatus.DONE);
    }

    private static AgentRun verify(AgentRequest request, int round) {
        assertThat(toolNames(request)).contains("submit_verdict", "read_file")
                .doesNotContain("record_fact", "record_finding");
        assertThat(request.userPrompt()).contains("테스트 발견", "UserServiceImpl.java:25", "입력이 그대로 쓰임");
        if (round == 1) {
            return new AgentRun(Outcome.STEP_LIMIT, "", 40, 100, 50, 0, null);
        }
        String verdict = round % 2 == 0 ? "confirmed" : "rejected";
        call(request, "submit_verdict", Map.of("verdict", verdict, "reasoning", "근거", "confidence", "high"));
        assertThatThrownBy(() -> call(request, "submit_verdict", Map.of("verdict", verdict, "reasoning", "다시", "confidence", "high")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("이미 판정");
        return new AgentRun(Outcome.COMPLETED, "판정 제출함", 2, 100, 50, 0, null);
    }

    private static List<String> toolNames(AgentRequest request) {
        return request.tools().stream().map(AgentTool::name).toList();
    }

    private static String call(AgentRequest request, String toolName, Map<String, Object> input) {
        AgentTool tool = request.tools().stream().filter(t -> t.name().equals(toolName)).findFirst().orElseThrow();
        return tool.execute(new ToolInput(input), request.context());
    }

    private AnalysisJob waitUntilFinished(Long jobId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.RUNNING) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("분석 작업이 10초 안에 끝나지 않음");
    }
}
