package com.codeagent.api;

import com.codeagent.analysisgraph.AnalysisEvents;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository.NodeCount;
import com.codeagent.analysisgraph.domain.ReviewDecision;
import com.codeagent.analysisgraph.domain.WorkerTrace;
import com.codeagent.analysisgraph.domain.WorkerTraceRepository;
import com.codeagent.llm.AgentProperties;
import com.codeagent.orchestrator.AnalysisService;
import com.codeagent.orchestrator.PlannerMode;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.JobStatus;
import com.codeagent.project.ProjectRepository;
import com.codeagent.report.HtmlReportWriter;
import com.codeagent.report.ReportService;
import com.codeagent.report.SarifWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class AnalysisController {

    private static final MediaType SARIF = MediaType.parseMediaType("application/sarif+json");

    private final AnalysisService analysisService;
    private final ProjectRepository projectRepository;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final WorkerTraceRepository traceRepository;
    private final JsonMapper jsonMapper;
    private final AnalysisGraphService analysisGraph;
    private final ReportService reportService;
    private final AgentProperties agentProperties;
    private final AnalysisEvents analysisEvents;

    /**
     * @param maxIntentions 할 일 수 상한 (경로 계획이면 조사할 경로 수). 생략하면 LLM 계획 10, 경로 계획 5
     * @param planner       llm(계획 담당이 할 일을 정함) 또는 paths(경로마다 기계적으로). 생략하면 codeagent.agent.planner
     * @param requireApproval true 면 계획이 나올 때마다 사람이 승인해야 Worker 가 돈다. 생략하면 codeagent.agent.require-approval
     */
    public record StartAnalysisRequest(@Min(1) @Max(100) Integer maxIntentions, @Min(10_000) Long budgetTokens, String planner,
                                       Boolean requireApproval) {
    }

    /** @param skipIntentionIds 실행하지 않을 할 일 (승인 대기 중인 할 일만) */
    public record ApproveRequest(List<Long> skipIntentionIds, String comment) {
    }

    public record RejectRequest(String comment) {
    }

    public record JobView(Long id, Long projectId, String status, Long budgetTokens, long usedTokens,
                          long inputTokens, long outputTokens, String error,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt) {

        static JobView from(AnalysisJob job) {
            return new JobView(job.getId(), job.getProject().getId(), job.getStatus().name(), job.getBudgetTokens(),
                    job.getUsedTokens(), job.getInputTokens(), job.getOutputTokens(), job.getError(),
                    job.getStartedAt(), job.getFinishedAt());
        }
    }

    public record ReviewRequest(@NotNull ReviewDecision decision, String comment) {
    }

    public record NodeView(Long id, String kind, String status, String title, String body, JsonNode props,
                           String workerId) {
    }

    public record AnalysisView(JobView job, List<NodeView> nodes) {
    }

    /**
     * 분석 목록의 한 줄.
     *
     * @param intentionsFinished 끝난(DONE / FAILED) 할 일 수
     * @param findings           발견 상태(OPEN / CONFIRMED / REJECTED / UNCERTAIN)별 개수. 없는 상태는 0
     */
    public record JobSummaryView(JobView job, long intentions, long intentionsFinished, Map<String, Long> findings) {
    }

    public record TraceView(Long id, Long intentionId, String workerId, int step, String role, String toolName,
                            String content, OffsetDateTime createdAt) {

        static TraceView from(WorkerTrace t) {
            return new TraceView(t.getId(), t.getIntentionId(), t.getWorkerId(), t.getStep(), t.getRole(),
                    t.getToolName(), t.getContent(), t.getCreatedAt());
        }
    }

    @PostMapping("/api/projects/{projectId}/analyses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView start(@PathVariable Long projectId, @Valid @RequestBody(required = false) StartAnalysisRequest request) {
        PlannerMode planner = request == null || request.planner() == null || request.planner().isBlank()
                ? agentProperties.planner() : PlannerMode.parse(request.planner());
        int maxIntentions = request == null || request.maxIntentions() == null
                ? (planner == PlannerMode.LLM ? 10 : 5) : request.maxIntentions();
        long budget = request == null || request.budgetTokens() == null ? 2_000_000L : request.budgetTokens();
        boolean requireApproval = request == null || request.requireApproval() == null
                ? agentProperties.requireApproval() : request.requireApproval();
        AnalysisJob job = analysisService.start(projectId, maxIntentions, budget, planner, requireApproval);
        return JobView.from(jobRepository.findById(job.getId()).orElseThrow());
    }

    /** 프로젝트의 분석 작업 목록 (최신순). 노드는 읽지 않고 할 일·발견 개수만 센다 */
    @GetMapping("/api/projects/{projectId}/analyses")
    public List<JobSummaryView> list(@PathVariable Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new NoSuchElementException("프로젝트 없음: " + projectId);
        }
        List<AnalysisJob> jobs = jobRepository.findByProjectIdOrderByIdDesc(projectId);
        if (jobs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NodeCount>> counts = nodeRepository.countByJob(jobs.stream().map(AnalysisJob::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(NodeCount::getJobId));

        return jobs.stream().map(job -> {
            List<NodeCount> jobCounts = counts.getOrDefault(job.getId(), List.of());
            long intentions = 0;
            long intentionsFinished = 0;
            Map<String, Long> findings = new LinkedHashMap<>();
            for (String status : List.of("OPEN", "CONFIRMED", "REJECTED", "UNCERTAIN")) {
                findings.put(status, 0L);
            }
            for (NodeCount c : jobCounts) {
                if (c.getKind().equals(AnalysisNodeKind.INTENTION.name())) {
                    intentions += c.getCount();
                    if (c.getStatus().equals("DONE") || c.getStatus().equals("FAILED")) {
                        intentionsFinished += c.getCount();
                    }
                } else {
                    findings.merge(c.getStatus(), c.getCount(), Long::sum);
                }
            }
            return new JobSummaryView(JobView.from(job), intentions, intentionsFinished, findings);
        }).toList();
    }

    /**
     * 작업의 변화를 실시간으로 받는다 (Server-Sent Events).
     * 이벤트: ready(연결됨) → change(분석 그래프·기록이 바뀜, data 는 node/trace/verdict/review/job) → finished(끝남, 연결 닫힘).
     * 이벤트를 받으면 GET /api/analyses/{jobId}, /traces 를 다시 불러온다. 이미 끝난 작업이면 ready·finished 를 보내고 닫는다.
     */
    @GetMapping(value = "/api/analyses/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        boolean finished = job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.RUNNING;
        return ResponseEntity.ok()
                // nginx 같은 리버스 프록시가 이벤트를 모아 두지 않고 바로 흘려보내게 한다 (Docker 의 웹 화면)
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(analysisEvents.subscribe(jobId, job.getStatus().name(), finished));
    }

    @GetMapping("/api/analyses/{jobId}")
    public AnalysisView get(@PathVariable Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        List<NodeView> nodes = nodeRepository.findByJobIdOrderById(jobId).stream().map(this::nodeView).toList();
        return new AnalysisView(JobView.from(job), nodes);
    }

    /**
     * 승인 대기(WAITING_APPROVAL) 중인 계획을 승인하고 이어서 진행한다. skipIntentionIds 의 할 일은 건너뛴다.
     * 승인 대기가 아니면 400.
     */
    @PostMapping("/api/analyses/{jobId}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView approve(@PathVariable Long jobId, @RequestBody(required = false) ApproveRequest request) {
        AnalysisJob job = analysisService.approve(jobId,
                request == null ? List.of() : request.skipIntentionIds(), request == null ? null : request.comment());
        return JobView.from(job);
    }

    /** 승인 대기 중인 계획을 거절하고 작업을 멈춘다 (STOPPED). 남은 할 일은 건너뛴다 */
    @PostMapping("/api/analyses/{jobId}/reject")
    public JobView reject(@PathVariable Long jobId, @RequestBody(required = false) RejectRequest request) {
        return JobView.from(analysisService.reject(jobId, request == null ? null : request.comment()));
    }

    /** 검증 대기(OPEN) 발견을 다시 검증한다. 끝난 작업에만 쓸 수 있다 */
    @PostMapping("/api/analyses/{jobId}/verify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView verify(@PathVariable Long jobId) {
        analysisService.verify(jobId);
        return JobView.from(jobRepository.findById(jobId).orElseThrow());
    }

    /**
     * @param status OPEN(검증 대기) / CONFIRMED / REJECTED / UNCERTAIN, 생략하면 전체
     */
    @GetMapping("/api/analyses/{jobId}/findings")
    public List<NodeView> findings(@PathVariable Long jobId, @RequestParam(required = false) String status) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind().name().equals("FINDING"))
                .filter(n -> status == null || n.getStatus().name().equalsIgnoreCase(status))
                .map(this::nodeView)
                .toList();
    }

    /**
     * 사람이 발견을 검토한 결과를 남긴다 (ACCEPTED / FALSE_POSITIVE / FIXED / WONT_FIX).
     * 검증 담당의 판정은 그대로 두고 props.review 에 기록한다. 다시 호출하면 덮어쓴다.
     */
    @PostMapping("/api/analyses/{jobId}/findings/{findingId}/review")
    public NodeView review(@PathVariable Long jobId, @PathVariable Long findingId,
                           @Valid @RequestBody ReviewRequest request) {
        return nodeView(analysisGraph.recordReview(jobId, findingId, request.decision(), request.comment()));
    }

    /**
     * SARIF 2.1.0 보고서 (GitHub 코드 스캐닝 등에 올릴 수 있음).
     *
     * @param includeDismissed 오탐(REJECTED, 사람이 FALSE_POSITIVE로 표시)도 넣을지
     */
    @GetMapping("/api/analyses/{jobId}/report.sarif")
    public ResponseEntity<String> sarifReport(@PathVariable Long jobId,
                                              @RequestParam(defaultValue = "false") boolean includeDismissed) {
        String body = SarifWriter.write(reportService.build(jobId, includeDismissed), jsonMapper);
        return ResponseEntity.ok()
                .contentType(SARIF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis-" + jobId + ".sarif\"")
                .body(body);
    }

    /** 브라우저로 바로 볼 수 있는 HTML 보고서 */
    @GetMapping("/api/analyses/{jobId}/report.html")
    public ResponseEntity<String> htmlReport(@PathVariable Long jobId,
                                             @RequestParam(defaultValue = "false") boolean includeDismissed) {
        String body = HtmlReportWriter.write(reportService.build(jobId, includeDismissed));
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                // 보고서에는 LLM이 쓴 글과 분석 대상 코드가 들어가므로 스크립트 실행을 막는다
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(body);
    }

    @GetMapping("/api/analyses/{jobId}/traces")
    public List<TraceView> traces(@PathVariable Long jobId, @RequestParam(required = false) Long intentionId) {
        List<WorkerTrace> traces = intentionId == null
                ? traceRepository.findByJobIdOrderById(jobId)
                : traceRepository.findByJobIdAndIntentionIdOrderById(jobId, intentionId);
        return traces.stream().map(TraceView::from).toList();
    }

    private NodeView nodeView(AnalysisNode node) {
        return new NodeView(node.getId(), node.getKind().name(), node.getStatus().name(), node.getTitle(),
                node.getBody(), jsonMapper.readTree(node.getProps()), node.getWorkerId());
    }
}
