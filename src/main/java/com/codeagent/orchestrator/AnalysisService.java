package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.AgentLoop.Outcome;
import com.codeagent.agent.PlannerPrompts;
import com.codeagent.agent.WorkerPrompts;
import com.codeagent.agent.tool.AddIntentionTool;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.FinishPlanningTool;
import com.codeagent.agent.tool.GetPlanTool;
import com.codeagent.agent.tool.ListEntryPointsTool;
import com.codeagent.agent.tool.ListSinkPathsTool;
import com.codeagent.agent.tool.ToolContext;
import com.codeagent.analysisgraph.AnalysisEvents;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import com.codeagent.llm.AgentProperties;
import com.codeagent.llm.ModelTier;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.JobStatus;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * 분석 작업 하나를 진행한다.
 * <ul>
 *   <li>LLM 계획: 계획 담당이 할 일을 정함 → 실행 담당 여러 개가 나눠 처리 → 계획 담당이 결과를 보고 할 일을 보완 (최대 N회차)</li>
 *   <li>경로 계획: 진입점 → 위험 지점 경로마다 할 일을 기계적으로 만들고 실행 담당이 처리</li>
 * </ul>
 * 할 일이 모두 끝나면 검증 담당이 발견을 하나씩 다시 확인한다.
 */
@Slf4j
@Service
public class AnalysisService {

    private static final int PATH_DEPTH = 12;
    static final String PLANNER_ID = "planner";

    /** 실행 담당이 쓰는 도구. 판정 제출과 계획 도구는 쓰지 않는다 */
    static final Set<String> WORKER_TOOLS = Set.of(
            "read_file", "grep_code", "find_code_nodes", "get_callers", "get_callees", "search_worker_traces",
            "record_fact", "record_finding");

    /** 계획 담당이 쓰는 도구. 코드를 읽을 수는 있지만 사실·발견은 기록하지 않는다 */
    static final Set<String> PLANNER_TOOLS = Set.of(
            ListSinkPathsTool.NAME, ListEntryPointsTool.NAME, GetPlanTool.NAME, AddIntentionTool.NAME, FinishPlanningTool.NAME,
            "read_file", "grep_code", "find_code_nodes", "get_callers", "get_callees", "search_worker_traces");

    private final ProjectRepository projectRepository;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;
    private final AgentLoop agentLoop;
    private final VerificationService verificationService;
    private final List<AgentTool> workerTools;
    private final List<AgentTool> plannerTools;
    private final AgentProperties agentProperties;
    private final AnalysisEvents events;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AnalysisService(ProjectRepository projectRepository, AnalysisJobRepository jobRepository,
                           AnalysisNodeRepository nodeRepository, AnalysisGraphService analysisGraph,
                           CodeGraphQuery codeGraphQuery, AgentLoop agentLoop,
                           VerificationService verificationService, List<AgentTool> allTools,
                           AgentProperties agentProperties, AnalysisEvents events) {
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.nodeRepository = nodeRepository;
        this.analysisGraph = analysisGraph;
        this.codeGraphQuery = codeGraphQuery;
        this.agentLoop = agentLoop;
        this.verificationService = verificationService;
        this.workerTools = allTools.stream().filter(t -> WORKER_TOOLS.contains(t.name())).toList();
        this.plannerTools = allTools.stream().filter(t -> PLANNER_TOOLS.contains(t.name())).toList();
        this.agentProperties = agentProperties;
        this.events = events;
    }

    /**
     * 분석 작업을 만들고 백그라운드에서 시작한다.
     *
     * @param maxIntentions 할 일 수 상한 (경로 계획이면 조사할 경로 수)
     * @param budgetTokens  작업 전체 토큰 예산
     * @param planner       할 일을 만드는 방식
     * @param requireApproval true 면 계획(할 일 목록)이 나올 때마다 사람이 승인해야 Worker 가 돈다
     */
    public AnalysisJob start(Long projectId, int maxIntentions, long budgetTokens, PlannerMode planner,
                             boolean requireApproval) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
        List<SinkPathView> paths = planner == PlannerMode.PATHS
                ? shortestPathPerPair(codeGraphQuery.sinkPaths(projectId, PATH_DEPTH), maxIntentions)
                : List.of();
        if (planner == PlannerMode.PATHS && paths.isEmpty()) {
            throw new IllegalArgumentException("진입점에서 위험 지점까지 가는 경로가 없음. 코드 그래프를 먼저 만들었는지 확인하고, "
                    + "경로가 없으면 planner=llm 으로 권한 검사 누락 같은 문제를 찾게 할 것");
        }
        if (planner == PlannerMode.LLM && codeGraphQuery.entryPoints(projectId).isEmpty() && codeGraphQuery.sinks(projectId).isEmpty()) {
            throw new IllegalArgumentException("코드 그래프에 진입점도 위험 지점도 없음. 코드 그래프를 먼저 만들 것");
        }

        AnalysisJob job = new AnalysisJob(project, budgetTokens);
        job.start();
        jobRepository.save(job);

        Map<String, Object> goalProps = new LinkedHashMap<>();
        goalProps.put("planner", planner.name());
        goalProps.put("maxIntentions", maxIntentions);
        goalProps.put("plannerRound", 0);
        goalProps.put("requireApproval", requireApproval);
        // 승인한 계획 회차. 경로 계획은 0회차 계획(만든 경로 할 일)을 승인받는다
        goalProps.put("approvedRound", -1);
        AnalysisNode goal = analysisGraph.addNode(job.getId(), AnalysisNodeKind.GOAL, project.getName() + " 보안 취약점 분석",
                planner == PlannerMode.LLM
                        ? "계획 담당이 할 일을 정함 (최대 " + agentProperties.plannerRounds() + "회차, 할 일 상한 " + maxIntentions + "개)"
                        : "진입점 → 위험 지점 경로 " + paths.size() + "개 조사",
                goalProps);
        for (SinkPathView path : paths) {
            analysisGraph.addIntention(job.getId(), goal.getId(),
                    path.entryPoint().httpMethod() + " " + path.entryPoint().path() + " → " + path.sink().api(),
                    WorkerPrompts.intention(path),
                    Map.of("entryNodeId", path.entryPoint().id(), "sinkNodeId", path.sink().id(),
                            "category", path.sink().category(), "cwe", path.sink().cwe()),
                    path.entryPoint().id(), path.sink().id(),
                    path.path().stream().filter(n -> "METHOD".equals(n.kind())).map(CodeNodeView::id).toList(),
                    List.of());
        }
        if (planner == PlannerMode.PATHS) {
            analysisGraph.complete(goal.getId());
        }

        Long jobId = job.getId();
        Path repoRoot = Path.of(project.getRepoPath());
        executor.submit(() -> runJob(jobId, project, repoRoot, goal.getId(), planner));
        return job;
    }

    /** 실행 담당이 멈춘 이유 */
    private enum WorkerExit { DRAINED, BUDGET_EXHAUSTED, FAILED }

    /** 단계가 비정상으로 멈췄을 때 작업을 끝낼 상태와 이유 */
    private record Stop(JobStatus status, String error) {
    }

    /**
     * 계획 → 실행 → (계획 → 실행)… → 검증. 경로 계획이면 계획 단계 없이 실행부터 한다.
     */
    private void runJob(Long jobId, Project project, Path repoRoot, Long goalId, PlannerMode planner) {
        guard(jobId, () -> {
            if (planner == PlannerMode.LLM) {
                Stop stop = runPlanner(jobId, project, repoRoot, goalId, 1);
                if (stop != null) {
                    analysisGraph.complete(goalId);
                    finish(jobId, stop.status(), stop.error());
                    return;
                }
            }
            advance(jobId, project, repoRoot, goalId, planner);
        });
    }

    /**
     * 지금 계획부터 끝까지 진행한다: (승인) → 실행 → 계획 다음 회차 → (승인) → … → 검증.
     * 승인이 필요하면 작업을 WAITING_APPROVAL 로 두고 돌아간다. {@link #approve} 가 이 메서드로 이어서 진행한다.
     */
    private void advance(Long jobId, Project project, Path repoRoot, Long goalId, PlannerMode planner)
            throws InterruptedException, ExecutionException {
        if (pauseForApproval(jobId, goalId)) {
            return;
        }
        Stop stop;
        while (true) {
            stop = runWorkers(jobId, project.getId(), repoRoot);
            int round = plannerRound(goalId);
            if (stop != null || planner != PlannerMode.LLM || round >= agentProperties.plannerRounds() || planningFinished(goalId)) {
                break;
            }
            long before = countIntentions(jobId);
            stop = runPlanner(jobId, project, repoRoot, goalId, round + 1);
            if (stop != null || countIntentions(jobId) == before) {
                break; // 계획 담당이 더 만들 할 일이 없다고 판단함
            }
            if (pauseForApproval(jobId, goalId)) {
                return;
            }
        }
        if (planner == PlannerMode.LLM) {
            analysisGraph.complete(goalId);
        }
        if (stop != null) {
            finish(jobId, stop.status(), stop.error());
        } else {
            runVerification(jobId, project.getId(), repoRoot);
        }
    }

    /** 승인이 필요하고 아직 승인받지 않은 회차의 할 일이 남아 있으면 작업을 승인 대기로 둔다 */
    private boolean pauseForApproval(Long jobId, Long goalId) {
        AnalysisNode goal = nodeRepository.findById(goalId).orElseThrow();
        Map<String, Object> props = analysisGraph.props(goal);
        boolean required = Boolean.TRUE.equals(props.get("requireApproval"));
        int approved = ((Number) props.getOrDefault("approvedRound", -1)).intValue();
        if (!required || approved >= plannerRound(goalId) || openIntentions(jobId).isEmpty()) {
            return false;
        }
        jobRepository.transition(jobId, JobStatus.RUNNING, JobStatus.WAITING_APPROVAL);
        events.changed(jobId, "job");
        log.info("분석 작업 #{} 계획 {}회차 승인 대기 (할 일 {}개)", jobId, plannerRound(goalId), openIntentions(jobId).size());
        return true;
    }

    /**
     * 승인 대기 중인 계획을 승인하고 이어서 진행한다.
     *
     * @param skipIntentionIds 실행하지 않을 할 일 (대기 중인 할 일만)
     */
    public AnalysisJob approve(Long jobId, List<Long> skipIntentionIds, String comment) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        AnalysisNode goal = goalOf(jobId);
        Set<Long> open = openIntentions(jobId).stream().map(AnalysisNode::getId).collect(java.util.stream.Collectors.toSet());
        List<Long> skip = skipIntentionIds == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(skipIntentionIds));
        for (Long id : skip) {
            if (!open.contains(id)) {
                throw new IllegalArgumentException("이 작업의 대기 중인 할 일이 아님: " + id);
            }
        }
        if (jobRepository.transition(jobId, JobStatus.WAITING_APPROVAL, JobStatus.RUNNING) == 0) {
            throw new IllegalArgumentException("승인 대기 중인 작업이 아님: " + jobId + " (" + job.getStatus() + ")");
        }
        skip.forEach(analysisGraph::skip);
        recordDecision(goal.getId(), "APPROVED", skip, comment);
        events.changed(jobId, "job");
        log.info("분석 작업 #{} 계획 승인 (뺀 할 일 {}개)", jobId, skip.size());

        Project project = projectRepository.findById(job.getProject().getId()).orElseThrow();
        PlannerMode planner = PlannerMode.valueOf((String) analysisGraph.props(goal).get("planner"));
        executor.submit(() -> guard(jobId, () -> advance(jobId, project, Path.of(project.getRepoPath()), goal.getId(), planner)));
        return jobRepository.findById(jobId).orElseThrow();
    }

    /** 승인 대기 중인 계획을 거절하고 작업을 멈춘다. 남은 할 일은 건너뛴다 */
    public AnalysisJob reject(Long jobId, String comment) {
        jobRepository.findById(jobId).orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        AnalysisNode goal = goalOf(jobId);
        if (jobRepository.transition(jobId, JobStatus.WAITING_APPROVAL, JobStatus.STOPPED) == 0) {
            throw new IllegalArgumentException("승인 대기 중인 작업이 아님: " + jobId);
        }
        List<Long> skipped = openIntentions(jobId).stream().map(AnalysisNode::getId).toList();
        skipped.forEach(analysisGraph::skip);
        recordDecision(goal.getId(), "REJECTED", skipped, comment);
        analysisGraph.complete(goal.getId());
        finish(jobId, JobStatus.STOPPED, "사람이 계획을 거절함" + (comment == null || comment.isBlank() ? "" : ": " + comment.strip()));
        return jobRepository.findById(jobId).orElseThrow();
    }

    /** 승인·거절 기록을 목표 props.approvals 에 남기고, 승인한 회차를 올린다 */
    private void recordDecision(Long goalId, String decision, List<Long> skipped, String comment) {
        analysisGraph.updateProps(goalId, props -> {
            int round = ((Number) props.getOrDefault("plannerRound", 0)).intValue();
            props.put("approvedRound", round);
            List<Object> approvals = new ArrayList<>(props.get("approvals") instanceof List<?> list ? list : List.of());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("round", round);
            entry.put("decision", decision);
            entry.put("skipped", skipped);
            entry.put("comment", comment == null || comment.isBlank() ? null : comment.strip());
            entry.put("at", OffsetDateTime.now().toString());
            approvals.add(entry);
            props.put("approvals", approvals);
        });
    }

    @FunctionalInterface
    private interface JobStep {
        void run() throws InterruptedException, ExecutionException;
    }

    /** 백그라운드 단계에서 난 오류로 작업을 실패 처리한다 */
    private void guard(Long jobId, JobStep step) {
        try {
            step.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(jobId, JobStatus.FAILED, "중단됨");
        } catch (ExecutionException | RuntimeException e) {
            log.error("분석 작업 #{} 실패", jobId, e);
            finish(jobId, JobStatus.FAILED, e.getMessage());
        }
    }

    private AnalysisNode goalOf(Long jobId) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.GOAL)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("분석 작업의 목표 노드 없음: " + jobId));
    }

    private List<AnalysisNode> openIntentions(Long jobId) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.INTENTION && n.getStatus() == AnalysisNodeStatus.OPEN)
                .toList();
    }

    private int plannerRound(Long goalId) {
        return nodeRepository.findById(goalId)
                .map(goal -> ((Number) analysisGraph.props(goal).getOrDefault("plannerRound", 0)).intValue())
                .orElse(0);
    }

    /** 계획 담당 한 회차. 결론은 목표 아래 FACT("계획 N회차 결론")로 남긴다 */
    private Stop runPlanner(Long jobId, Project project, Path repoRoot, Long goalId, int round) {
        AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
        if (job.remainingTokens() == 0) {
            return new Stop(JobStatus.STOPPED, "토큰 예산 소진 (계획 중)");
        }
        Map<String, Object> goalProps = analysisGraph.updateProps(goalId, props -> props.put("plannerRound", round));
        int maxIntentions = ((Number) goalProps.get("maxIntentions")).intValue();
        log.info("분석 작업 #{} 계획 {}회차 시작", jobId, round);

        // 계획 담당의 기록은 목표 id 로 묶는다 (GET /traces?intentionId={목표 id})
        ToolContext context = new ToolContext(project.getId(), repoRoot, jobId, goalId, PLANNER_ID);
        AgentRun run = agentLoop.run(new AgentRequest(ModelTier.PLANNER, PlannerPrompts.SYSTEM,
                PlannerPrompts.round(project.getName(), round, agentProperties.plannerRounds(), maxIntentions, (int) countIntentions(jobId)),
                plannerTools, context, agentProperties.maxSteps(), job.remainingTokens()));
        jobRepository.addUsage(jobId, run.inputTokens(), run.outputTokens());

        Map<String, Object> props = runProps(run, PLANNER_ID);
        props.put("round", round);
        AnalysisNode summary = analysisGraph.addNode(jobId, AnalysisNodeKind.FACT, "계획 " + round + "회차 결론", run.finalText(), props);
        analysisGraph.complete(summary.getId());
        analysisGraph.addEdge(jobId, goalId, summary.getId(), AnalysisEdgeKind.YIELDS);
        log.info("분석 작업 #{} 계획 {}회차 종료: {} (할 일 {}개, {}단계)", jobId, round, run.outcome(), countIntentions(jobId), run.steps());

        if (run.outcome() == Outcome.FAILED && run.steps() == 0) {
            return new Stop(JobStatus.FAILED, run.error() == null ? "LLM 호출 실패" : run.error());
        }
        if (run.outcome() == Outcome.BUDGET_EXHAUSTED) {
            return new Stop(JobStatus.STOPPED, "토큰 예산 소진 (계획 중)");
        }
        return null;
    }

    /** 실행 담당 여러 개를 동시에 돌린다. 가져갈 할 일이 없으면 모두 끝난다 */
    private Stop runWorkers(Long jobId, Long projectId, Path repoRoot) throws InterruptedException, ExecutionException {
        AtomicReference<String> failure = new AtomicReference<>();
        List<Future<WorkerExit>> workers = IntStream.rangeClosed(1, agentProperties.workers())
                .mapToObj(i -> executor.submit(() -> runWorker(jobId, projectId, repoRoot, "worker-" + i, failure)))
                .toList();
        List<WorkerExit> exits = new ArrayList<>();
        for (Future<WorkerExit> worker : workers) {
            exits.add(worker.get());
        }
        int blocked = analysisGraph.failBlockedIntentions(jobId);
        if (blocked > 0) {
            log.info("분석 작업 #{}: 선행 할 일이 실패해 시작할 수 없는 할 일 {}개를 실패로 표시", jobId, blocked);
        }
        if (exits.contains(WorkerExit.FAILED)) {
            return new Stop(JobStatus.FAILED, failure.get());
        }
        if (exits.contains(WorkerExit.BUDGET_EXHAUSTED)) {
            return new Stop(JobStatus.STOPPED, "토큰 예산 소진");
        }
        return null;
    }

    /**
     * 할 일을 하나씩 가져가 처리한다. 가져갈 할 일이 없거나, 예산이 바닥나거나, 다른 실행 담당이 치명적인 오류로 멈추면 끝낸다.
     */
    private WorkerExit runWorker(Long jobId, Long projectId, Path repoRoot, String workerId,
                                 AtomicReference<String> failure) {
        try {
            while (failure.get() == null) {
                AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
                if (job.remainingTokens() == 0) {
                    return WorkerExit.BUDGET_EXHAUSTED;
                }
                Optional<AnalysisNode> claimed = nodeRepository.claimNextIntention(jobId, workerId);
                if (claimed.isEmpty()) {
                    return WorkerExit.DRAINED;
                }
                AnalysisNode intention = claimed.get();
                log.info("[{}] 할 일 #{} 시작: {}", workerId, intention.getId(), intention.getTitle());

                ToolContext context = new ToolContext(projectId, repoRoot, jobId, intention.getId(), workerId);
                AgentRun run = agentLoop.run(new AgentRequest(ModelTier.WORKER, WorkerPrompts.SYSTEM,
                        intention.getBody(), workerTools, context, agentProperties.maxSteps(), job.remainingTokens()));
                recordRun(jobId, intention, run, workerId);

                if (run.outcome() == Outcome.FAILED && run.steps() == 0) {
                    // 첫 호출부터 실패하면 (인증 오류 등) 다른 할 일도 실패할 것이므로 모든 실행 담당을 멈춘다
                    failure.compareAndSet(null, run.error() == null ? "LLM 호출 실패" : run.error());
                    return WorkerExit.FAILED;
                }
            }
            return WorkerExit.FAILED;
        } catch (RuntimeException e) {
            log.error("[{}] 분석 작업 #{} 실패", workerId, jobId, e);
            failure.compareAndSet(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return WorkerExit.FAILED;
        }
    }

    /** 끝난 작업의 검증 대기 발견을 다시 검증한다 */
    public AnalysisJob verify(Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.WAITING_APPROVAL) {
            throw new IllegalArgumentException("분석 작업이 아직 진행 중임: " + jobId);
        }
        Project project = projectRepository.findById(job.getProject().getId()).orElseThrow();
        job.resume();
        jobRepository.save(job);
        events.changed(jobId, "job");
        executor.submit(() -> {
            try {
                runVerification(jobId, project.getId(), Path.of(project.getRepoPath()));
            } catch (RuntimeException e) {
                log.error("분석 작업 #{} 검증 실패", jobId, e);
                finish(jobId, JobStatus.FAILED, e.getMessage());
            }
        });
        return job;
    }

    private void runVerification(Long jobId, Long projectId, Path repoRoot) {
        VerificationService.VerificationSummary summary =
                verificationService.verifyOpenFindings(jobId, projectId, repoRoot);
        if (summary.budgetExhausted()) {
            finish(jobId, JobStatus.STOPPED, "토큰 예산 소진 (검증 중)");
        } else {
            finish(jobId, JobStatus.DONE, null);
        }
    }

    private void recordRun(Long jobId, AnalysisNode intention, AgentRun run, String workerId) {
        jobRepository.addUsage(jobId, run.inputTokens(), run.outputTokens());

        AnalysisNode summary = analysisGraph.addNode(jobId, AnalysisNodeKind.FACT,
                "할 일 #" + intention.getId() + " 결론", run.finalText(), runProps(run, workerId));
        analysisGraph.complete(summary.getId());
        analysisGraph.addEdge(jobId, intention.getId(), summary.getId(), AnalysisEdgeKind.YIELDS);

        if (run.outcome() == Outcome.COMPLETED) {
            analysisGraph.complete(intention.getId());
        } else {
            analysisGraph.fail(intention.getId());
        }
        log.info("[{}] 할 일 #{} 종료: {} ({}단계, 입력 {} / 출력 {} 토큰)", workerId, intention.getId(), run.outcome(),
                run.steps(), run.inputTokens(), run.outputTokens());
    }

    private static Map<String, Object> runProps(AgentRun run, String workerId) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("outcome", run.outcome().name());
        props.put("steps", run.steps());
        props.put("inputTokens", run.inputTokens());
        props.put("outputTokens", run.outputTokens());
        props.put("cacheReadTokens", run.cacheReadTokens());
        props.put("error", run.error());
        props.put("workerId", workerId);
        return props;
    }

    private boolean planningFinished(Long goalId) {
        return nodeRepository.findById(goalId)
                .map(goal -> Boolean.TRUE.equals(analysisGraph.props(goal).get("planningFinished")))
                .orElse(true);
    }

    private long countIntentions(Long jobId) {
        return nodeRepository.findByJobIdOrderById(jobId).stream().filter(n -> n.getKind() == AnalysisNodeKind.INTENTION).count();
    }

    private void finish(Long jobId, JobStatus status, String error) {
        jobRepository.finish(jobId, status, error, OffsetDateTime.now());
        events.finished(jobId, status.name());
        log.info("분석 작업 #{} 종료: {} {}", jobId, status, error == null ? "" : error);
    }

    /** 같은 (진입점, 위험 지점) 쌍은 가장 짧은 경로 하나만 남긴다. sinkPaths 는 짧은 순으로 온다 */
    private static List<SinkPathView> shortestPathPerPair(List<SinkPathView> paths, int limit) {
        Map<String, SinkPathView> byPair = new LinkedHashMap<>();
        for (SinkPathView path : paths) {
            byPair.putIfAbsent(path.entryPoint().id() + "->" + path.sink().id(), path);
        }
        return byPair.values().stream().limit(limit).toList();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
