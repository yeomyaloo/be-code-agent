package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.AgentLoop.Outcome;
import com.codeagent.agent.WorkerPrompts;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.ToolContext;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnchorRole;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 분석 작업 하나를 진행한다.
 * 지금은 코드 그래프의 진입점 → 위험 지점 경로마다 할 일을 만들고 실행 담당 하나가 차례로 처리한다.
 * (LLM 계획 담당, 병렬 실행 담당, 검증 담당은 다음 단계)
 */
@Slf4j
@Service
public class AnalysisService {

    private static final String WORKER_ID = "worker-1";
    private static final int PATH_DEPTH = 12;

    private final ProjectRepository projectRepository;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;
    private final AgentLoop agentLoop;
    private final List<AgentTool> tools;
    private final AgentProperties agentProperties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AnalysisService(ProjectRepository projectRepository, AnalysisJobRepository jobRepository,
                           AnalysisNodeRepository nodeRepository, AnalysisGraphService analysisGraph,
                           CodeGraphQuery codeGraphQuery, AgentLoop agentLoop, List<AgentTool> tools,
                           AgentProperties agentProperties) {
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.nodeRepository = nodeRepository;
        this.analysisGraph = analysisGraph;
        this.codeGraphQuery = codeGraphQuery;
        this.agentLoop = agentLoop;
        this.tools = tools;
        this.agentProperties = agentProperties;
    }

    /**
     * 분석 작업을 만들고 백그라운드에서 시작한다.
     *
     * @param maxIntentions 처리할 진입점 → 위험 지점 경로 수 상한
     * @param budgetTokens  작업 전체 토큰 예산
     */
    public AnalysisJob start(Long projectId, int maxIntentions, long budgetTokens) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
        List<SinkPathView> paths = shortestPathPerPair(codeGraphQuery.sinkPaths(projectId, PATH_DEPTH), maxIntentions);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("진입점에서 위험 지점까지 가는 경로가 없음. 코드 그래프를 먼저 만들었는지 확인할 것");
        }

        AnalysisJob job = new AnalysisJob(project, budgetTokens);
        job.start();
        jobRepository.save(job);

        AnalysisNode goal = analysisGraph.addNode(job.getId(), AnalysisNodeKind.GOAL,
                project.getName() + " 보안 취약점 분석", "진입점 → 위험 지점 경로 " + paths.size() + "개 조사", Map.of());
        for (SinkPathView path : paths) {
            AnalysisNode intention = analysisGraph.addNode(job.getId(), AnalysisNodeKind.INTENTION,
                    path.entryPoint().httpMethod() + " " + path.entryPoint().path() + " → " + path.sink().api(),
                    WorkerPrompts.intention(path),
                    Map.of("entryNodeId", path.entryPoint().id(), "sinkNodeId", path.sink().id(),
                            "category", path.sink().category(), "cwe", path.sink().cwe()));
            analysisGraph.addEdge(job.getId(), goal.getId(), intention.getId(), AnalysisEdgeKind.SPAWNS);
            analysisGraph.anchor(intention.getId(), List.of(path.entryPoint().id()), AnchorRole.SOURCE);
            analysisGraph.anchor(intention.getId(), List.of(path.sink().id()), AnchorRole.SINK);
            analysisGraph.anchor(intention.getId(),
                    path.path().stream().filter(n -> "METHOD".equals(n.kind())).map(CodeNodeView::id).toList(),
                    AnchorRole.EXAMINED);
        }
        analysisGraph.complete(goal.getId());

        Long jobId = job.getId();
        Path repoRoot = Path.of(project.getRepoPath());
        executor.submit(() -> runWorker(jobId, projectId, repoRoot));
        return job;
    }

    private void runWorker(Long jobId, Long projectId, Path repoRoot) {
        try {
            while (true) {
                AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
                if (job.remainingTokens() == 0) {
                    finish(jobId, JobStatus.STOPPED, "토큰 예산 소진");
                    return;
                }
                Optional<AnalysisNode> claimed = nodeRepository.claimNextIntention(jobId, WORKER_ID);
                if (claimed.isEmpty()) {
                    finish(jobId, JobStatus.DONE, null);
                    return;
                }
                AnalysisNode intention = claimed.get();
                log.info("할 일 #{} 시작: {}", intention.getId(), intention.getTitle());

                ToolContext context = new ToolContext(projectId, repoRoot, jobId, intention.getId(), WORKER_ID);
                AgentRun run = agentLoop.run(new AgentRequest(ModelTier.WORKER, WorkerPrompts.SYSTEM,
                        intention.getBody(), tools, context, agentProperties.maxSteps(), job.remainingTokens()));
                recordRun(jobId, intention, run);

                if (run.outcome() == Outcome.FAILED && run.steps() == 0) {
                    // 첫 호출부터 실패하면 (인증 오류 등) 다른 할 일도 실패할 것이므로 멈춘다
                    finish(jobId, JobStatus.FAILED, run.error());
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.error("분석 작업 #{} 실패", jobId, e);
            finish(jobId, JobStatus.FAILED, e.getMessage());
        }
    }

    private void recordRun(Long jobId, AnalysisNode intention, AgentRun run) {
        AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
        job.addUsage(run.inputTokens(), run.outputTokens());
        jobRepository.save(job);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("outcome", run.outcome().name());
        props.put("steps", run.steps());
        props.put("inputTokens", run.inputTokens());
        props.put("outputTokens", run.outputTokens());
        props.put("cacheReadTokens", run.cacheReadTokens());
        props.put("error", run.error());
        AnalysisNode summary = analysisGraph.addNode(jobId, AnalysisNodeKind.FACT,
                "할 일 #" + intention.getId() + " 결론", run.finalText(), props);
        analysisGraph.complete(summary.getId());
        analysisGraph.addEdge(jobId, intention.getId(), summary.getId(), AnalysisEdgeKind.YIELDS);

        if (run.outcome() == Outcome.COMPLETED) {
            analysisGraph.complete(intention.getId());
        } else {
            analysisGraph.fail(intention.getId());
        }
        log.info("할 일 #{} 종료: {} ({}단계, 입력 {} / 출력 {} 토큰)", intention.getId(), run.outcome(),
                run.steps(), run.inputTokens(), run.outputTokens());
    }

    private void finish(Long jobId, JobStatus status, String error) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.finish(status, error);
            jobRepository.save(job);
        });
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
