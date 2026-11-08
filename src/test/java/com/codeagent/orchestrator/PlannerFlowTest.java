package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.AgentLoop.Outcome;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.ToolInput;
import com.codeagent.agent.tool.ToolInputException;
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
 * LLM 계획 흐름. 실제 LLM 대신 가짜 AgentLoop 가 계획 담당·실행 담당 도구를 직접 호출한다.
 * 계획 1회차 → 실행 → 계획 2회차(결과 확인, 보완, 종료) → 실행 → 검증 순서를 확인한다.
 * 로컬 PostgreSQL(codeagent DB)이 필요하다.
 */
@SpringBootTest
class PlannerFlowTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app").toAbsolutePath();
    private static final Pattern SEARCH_PATH = Pattern.compile("GET /api/users/search \\(진입점 (\\d+)\\).*?\\(위험 지점 (\\d+)\\)");

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
    void 계획_담당이_할_일을_정하고_결과를_보고_보완한_뒤_끝낸다() throws InterruptedException {
        Project project = projectRepository.save(new Project("planner-flow-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());

        AtomicInteger plannerRounds = new AtomicInteger();
        List<String> workedOn = Collections.synchronizedList(new java.util.ArrayList<>());
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            if (request.tier() == ModelTier.PLANNER) {
                return plannerRounds.incrementAndGet() == 1 ? planRound1(request) : planRound2(request);
            }
            assertThat(request.tier()).isEqualTo(ModelTier.WORKER);
            assertThat(toolNames(request)).contains("read_file", "record_fact", "record_finding")
                    .doesNotContain("add_intention", "finish_planning", "get_plan", "submit_verdict");
            AnalysisNode intention = nodeRepository.findById(request.context().intentionId()).orElseThrow();
            workedOn.add(intention.getTitle());
            assertThat(request.userPrompt()).contains("계획 담당의 지시:");
            if (intention.getTitle().equals("사용자 검색 SQL 경로")) {
                assertThat(request.userPrompt()).contains("참고할 경로 정보:", "UserServiceImpl#findByName(String)");
                call(request, "record_fact", Map.of("title", "name 이 SQL 에 이어 붙여짐", "detail", "UserServiceImpl.java:24"));
                return new AgentRun(Outcome.COMPLETED, "판정: 취약\n근거: UserServiceImpl.java:24", 3, 100, 50, 0, null);
            }
            return new AgentRun(Outcome.COMPLETED, "판정: 취약하지 않음", 2, 80, 40, 0, null);
        });

        AnalysisJob job = analysisService.start(project.getId(), 3, 1_000_000, PlannerMode.LLM, false);
        AnalysisJob finished = waitUntilFinished(job.getId());

        assertThat(finished.getStatus()).isEqualTo(JobStatus.DONE);
        // 2회차에서 finish_planning 을 불렀으므로 3회차는 열리지 않는다
        assertThat(plannerRounds).hasValue(2);
        // 권한 점검은 SQL 경로 할 일에 의존하므로 그 뒤에 실행된다
        assertThat(workedOn).containsExactly("사용자 검색 SQL 경로", "소유자 확인 누락 점검", "다른 검색 API 에도 같은 패턴이 있는지");

        List<AnalysisNode> nodes = nodeRepository.findByJobIdOrderById(job.getId());
        List<AnalysisNode> intentions = nodes.stream().filter(n -> n.getKind() == AnalysisNodeKind.INTENTION).toList();
        assertThat(intentions).extracting(AnalysisNode::getStatus).containsOnly(AnalysisNodeStatus.DONE);
        assertThat(intentions).extracting(n -> analysisGraph.props(n).get("plannedRound")).containsExactly(1, 1, 2);
        assertThat(analysisGraph.props(intentions.get(0))).containsEntry("category", "SQL").containsEntry("cwe", "CWE-89");

        AnalysisNode goal = nodes.stream().filter(n -> n.getKind() == AnalysisNodeKind.GOAL).findFirst().orElseThrow();
        assertThat(goal.getStatus()).isEqualTo(AnalysisNodeStatus.DONE);
        assertThat(analysisGraph.props(goal)).containsEntry("planner", "LLM").containsEntry("planningFinished", true);
        assertThat(nodes).extracting(AnalysisNode::getTitle).contains("계획 1회차 결론", "계획 2회차 결론");
        assertThat(finished.getUsedTokens()).isEqualTo(2 * 300 + 150 + 2 * 120);
    }

    @Test
    void 선행_할_일이_실패하면_그_뒤의_할_일도_실패로_표시하고_작업은_끝낸다() throws InterruptedException {
        Project project = projectRepository.save(new Project("planner-flow-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());

        when(agentLoop.run(any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            if (request.tier() == ModelTier.PLANNER) {
                if (request.userPrompt().contains("계획 1회차")) {
                    String first = call(request, "add_intention", Map.of("title", "선행 조사", "instructions", "보안 설정을 본다"));
                    call(request, "add_intention", Map.of("title", "후속 조사", "instructions", "선행 결과로 판단",
                            "depends_on", List.of(intentionId(first))));
                } else {
                    call(request, "finish_planning", Map.of("reason", "더 할 일 없음"));
                }
                return new AgentRun(Outcome.COMPLETED, "계획함", 2, 10, 5, 0, null);
            }
            // 선행 조사가 단계 한도로 끝나지 못한다
            return new AgentRun(Outcome.STEP_LIMIT, "", 40, 10, 5, 0, null);
        });

        AnalysisJob job = analysisService.start(project.getId(), 5, 1_000_000, PlannerMode.LLM, false);
        AnalysisJob finished = waitUntilFinished(job.getId());

        assertThat(finished.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(nodeRepository.findByJobIdOrderById(job.getId()).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.INTENTION))
                .extracting(AnalysisNode::getTitle, AnalysisNode::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("선행 조사", AnalysisNodeStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple("후속 조사", AnalysisNodeStatus.FAILED));
    }

    private static AgentRun planRound1(AgentRequest request) {
        assertThat(request.userPrompt()).contains("계획 1회차 (최대 3회차). 할 일 상한 3개");
        assertThat(toolNames(request)).contains("list_sink_paths", "list_entry_points", "get_plan", "add_intention",
                "finish_planning", "read_file").doesNotContain("record_fact", "record_finding", "submit_verdict");

        String paths = call(request, "list_sink_paths", Map.of());
        Matcher m = SEARCH_PATH.matcher(paths);
        assertThat(m.find()).as(paths).isTrue();
        long entry = Long.parseLong(m.group(1));
        long sink = Long.parseLong(m.group(2));
        assertThat(call(request, "list_entry_points", Map.of())).contains("GET /api/users/health");

        String sql = call(request, "add_intention", Map.of("title", "사용자 검색 SQL 경로",
                "instructions", "name 파라미터가 SQL 에 닿는지 본다", "entry_node_id", entry, "sink_node_id", sink));
        assertThat(sql).startsWith("할 일 #").contains("1회차");
        assertThatThrownBy(() -> call(request, "add_intention", Map.of("title", "중복", "instructions", "x",
                "entry_node_id", entry, "sink_node_id", sink)))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("이미 할 일");
        assertThatThrownBy(() -> call(request, "add_intention", Map.of("title", "없는 경로", "instructions", "x",
                "entry_node_id", sink, "sink_node_id", sink)))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("ENTRY_POINT 가 아니라 SINK");
        call(request, "add_intention", Map.of("title", "소유자 확인 누락 점검", "instructions", "경로 변수로 조회하는 API 를 본다",
                "category", "AUTHZ", "cwe", "CWE-639", "depends_on", List.of(intentionId(sql))));
        assertThat(call(request, "list_sink_paths", Map.of())).contains("← " + sql.substring(0, sql.indexOf(' ', 5)));
        return new AgentRun(Outcome.COMPLETED, "SQL 경로와 권한 점검을 계획함", 5, 200, 100, 0, null);
    }

    private static AgentRun planRound2(AgentRequest request) {
        assertThat(request.userPrompt()).contains("계획 2회차").contains("get_plan");
        String plan = call(request, "get_plan", Map.of());
        assertThat(plan).contains("할 일 2 / 상한 3개", "[완료] 사용자 검색 SQL 경로 (1회차)", "결론: 판정: 취약", "[완료] 소유자 확인 누락 점검");

        call(request, "add_intention", Map.of("title", "다른 검색 API 에도 같은 패턴이 있는지", "instructions", "grep 으로 문자열 결합 쿼리를 찾는다"));
        assertThatThrownBy(() -> call(request, "add_intention", Map.of("title", "넘침", "instructions", "x")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("상한 3개");
        call(request, "finish_planning", Map.of("reason", "핵심 경로를 모두 다룸"));
        return new AgentRun(Outcome.COMPLETED, "후속 하나를 더하고 계획을 끝냄", 4, 200, 100, 0, null);
    }

    private static long intentionId(String addResult) {
        Matcher m = Pattern.compile("할 일 #(\\d+)").matcher(addResult);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static List<String> toolNames(AgentRequest request) {
        return request.tools().stream().map(AgentTool::name).toList();
    }

    private static String call(AgentRequest request, String toolName, Map<String, Object> input) {
        AgentTool tool = request.tools().stream().filter(t -> t.name().equals(toolName)).findFirst().orElseThrow();
        return tool.execute(new ToolInput(input), request.context());
    }

    private AnalysisJob waitUntilFinished(Long jobId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.RUNNING) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("분석 작업이 끝나지 않음: " + jobId);
    }
}
