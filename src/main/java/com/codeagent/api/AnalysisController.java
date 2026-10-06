package com.codeagent.api;

import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.WorkerTrace;
import com.codeagent.analysisgraph.domain.WorkerTraceRepository;
import com.codeagent.orchestrator.AnalysisService;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final WorkerTraceRepository traceRepository;
    private final JsonMapper jsonMapper;

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

    @GetMapping("/api/analyses/{jobId}/findings")
    public List<NodeView> findings(@PathVariable Long jobId) {
        return nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind().name().equals("FINDING"))
                .map(this::nodeView)
                .toList();
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
