package com.codeagent.api;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.ReviewDecision;
import com.codeagent.analysisgraph.domain.WorkerTrace;
import com.codeagent.analysisgraph.domain.WorkerTraceRepository;
import com.codeagent.orchestrator.AnalysisService;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
public class AnalysisController {

    private static final MediaType SARIF = MediaType.parseMediaType("application/sarif+json");

    private final AnalysisService analysisService;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final WorkerTraceRepository traceRepository;
    private final JsonMapper jsonMapper;
    private final AnalysisGraphService analysisGraph;
    private final ReportService reportService;

    public record StartAnalysisRequest(@Min(1) @Max(100) Integer maxIntentions, @Min(10_000) Long budgetTokens) {
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
        int maxIntentions = request == null || request.maxIntentions() == null ? 5 : request.maxIntentions();
        long budget = request == null || request.budgetTokens() == null ? 2_000_000L : request.budgetTokens();
        AnalysisJob job = analysisService.start(projectId, maxIntentions, budget);
        return JobView.from(jobRepository.findById(job.getId()).orElseThrow());
    }

    @GetMapping("/api/analyses/{jobId}")
    public AnalysisView get(@PathVariable Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        List<NodeView> nodes = nodeRepository.findByJobIdOrderById(jobId).stream().map(this::nodeView).toList();
        return new AnalysisView(JobView.from(job), nodes);
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
