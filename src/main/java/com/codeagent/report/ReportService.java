package com.codeagent.report;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.analysisgraph.domain.ReviewDecision;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 분석 작업의 발견을 보고서용으로 모은다. 형식(SARIF, HTML)은 각 Writer가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "info");

    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisGraphService analysisGraph;
    private final CodeGraphQuery codeGraphQuery;

    public record Evidence(String file, int line, String note) {
    }

    /**
     * @param severity    검증 담당이 다시 매긴 심각도, 없으면 실행 담당이 매긴 심각도
     * @param status      검증 판정 (OPEN = 검증 대기)
     * @param entryPoint  예: GET /api/users/search
     */
    public record ReportFinding(Long id, String title, String description, String cwe, String severity,
                                String confidence, String status, String verdictReasoning, String blockingControls,
                                String exploitScenario, List<Evidence> evidence, String entryPoint, String sink,
                                String reviewDecision, String reviewComment) {

        /** 오탐으로 판정됐거나 사람이 오탐으로 표시한 발견 */
        public boolean dismissed() {
            return AnalysisNodeStatus.REJECTED.name().equals(status)
                    || ReviewDecision.FALSE_POSITIVE.name().equals(reviewDecision);
        }
    }

    public record Report(Long jobId, String jobStatus, String projectName, String gitUrl, String gitBranch,
                         String commitSha, long usedTokens, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                         OffsetDateTime generatedAt, List<ReportFinding> findings, int dismissedCount) {
    }

    /**
     * @param includeDismissed 오탐(REJECTED, 사람이 FALSE_POSITIVE로 표시)도 넣을지
     */
    @Transactional(readOnly = true)
    public Report build(Long jobId, boolean includeDismissed) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("분석 작업 없음: " + jobId));
        Project project = job.getProject();

        List<ReportFinding> all = nodeRepository.findByJobIdOrderById(jobId).stream()
                .filter(n -> n.getKind() == AnalysisNodeKind.FINDING)
                .map(n -> toFinding(n, project.getId()))
                .sorted(Comparator.comparingInt((ReportFinding f) -> severityRank(f.severity()))
                        .thenComparingInt(f -> statusRank(f.status()))
                        .thenComparing(ReportFinding::id))
                .toList();
        List<ReportFinding> findings = includeDismissed ? all : all.stream().filter(f -> !f.dismissed()).toList();

        return new Report(job.getId(), job.getStatus().name(), project.getName(), project.getGitUrl(),
                project.getGitBranch(), project.getCommitSha(), job.getUsedTokens(), job.getStartedAt(),
                job.getFinishedAt(), OffsetDateTime.now(), findings, (int) all.stream().filter(ReportFinding::dismissed).count());
    }

    private ReportFinding toFinding(AnalysisNode node, Long projectId) {
        Map<String, Object> props = analysisGraph.props(node);
        Map<String, Object> verification = map(props.get("verification"));
        Map<String, Object> review = map(props.get("review"));

        String severity = string(verification.get("severity"));
        if (severity == null) {
            severity = string(props.get("severity"));
        }
        if (severity == null || !SEVERITIES.contains(severity)) {
            severity = "medium";
        }
        String entryPoint = codeNode(projectId, props.get("sourceNodeId"))
                .map(n -> n.qualifiedName().split(" -> ")[0])
                .orElse(null);
        String sink = codeNode(projectId, props.get("sinkNodeId"))
                .map(n -> n.qualifiedName().substring(n.qualifiedName().lastIndexOf(' ') + 1))
                .orElse(null);

        return new ReportFinding(node.getId(), node.getTitle(), node.getBody(), string(props.get("cwe")), severity,
                string(props.get("confidence")), node.getStatus().name(), string(verification.get("reasoning")),
                string(verification.get("blockingControls")), string(props.get("exploitScenario")),
                evidence(props.get("evidence")), entryPoint, sink, string(review.get("decision")),
                string(review.get("comment")));
    }

    private java.util.Optional<CodeNodeView> codeNode(Long projectId, Object id) {
        return id instanceof Number n ? codeGraphQuery.node(projectId, n.longValue()) : java.util.Optional.empty();
    }

    private static List<Evidence> evidence(Object value) {
        List<Evidence> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> e = map(item);
                String file = string(e.get("file"));
                if (file == null) {
                    continue;
                }
                result.add(new Evidence(normalizePath(file), Math.max(1, toInt(e.get("line"))), string(e.get("note"))));
            }
        }
        return result;
    }

    /** 보고서의 파일 경로는 저장소 루트 기준, 슬래시로 */
    static String normalizePath(String file) {
        String path = file.replace('\\', '/');
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.startsWith("./") ? path.substring(2) : path.substring(1);
        }
        return path;
    }

    static int severityRank(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? SEVERITIES.size() : i;
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "CONFIRMED" -> 0;
            case "OPEN" -> 1;
            case "UNCERTAIN" -> 2;
            default -> 3;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        return 1;
    }
}
