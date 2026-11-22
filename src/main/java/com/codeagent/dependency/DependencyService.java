package com.codeagent.dependency;

import com.codeagent.dependency.DependencyScanner.ScanResult;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 저장소의 라이브러리를 빌드 파일에서 읽고, 알려진 취약점(OSV)을 붙인다. (OWASP A03 공급망)
 */
@Service
@RequiredArgsConstructor
public class DependencyService {

    private static final List<String> SEVERITY_ORDER = List.of("critical", "high", "medium", "low");

    private static final int HISTORY_LIMIT = 50;

    private final ProjectRepository projectRepository;
    private final OsvClient osvClient;
    private final DependencyProperties properties;
    private final DependencyScanRepository scanRepository;
    private final JsonMapper jsonMapper;

    /** 저장한 검사 한 번과 지난 검사와의 비교 (첫 검사면 diff 는 null) */
    public record ScanView(Long id, Long projectId, String commitSha, OffsetDateTime scannedAt, DependencyReport report,
                           DependencyDiff diff) {
    }

    /** 검사 기록 목록의 한 줄 */
    public record ScanSummary(Long id, String commitSha, OffsetDateTime scannedAt, boolean checked, String error,
                              int dependencies, int vulnerable, int vulnerabilities) {

        static ScanSummary from(DependencyScan scan) {
            return new ScanSummary(scan.getId(), scan.getCommitSha(), scan.getCreatedAt(), scan.isChecked(), scan.getError(),
                    scan.getDependencyCount(), scan.getVulnerableCount(), scan.getVulnerabilityCount());
        }
    }

    /** 라이브러리 하나와 그 취약점 */
    public record DependencyView(String group, String artifact, String version, String file, int line, String scope,
                                 List<Vulnerability> vulnerabilities) {
    }

    /**
     * @param files       읽은 빌드 파일
     * @param unversioned 버전을 알 수 없어 검사하지 못한 라이브러리
     * @param checked     OSV 에 물어봤는지 (꺼져 있거나 실패하면 false)
     * @param error       OSV 에 묻지 못한 이유
     */
    public record DependencyReport(List<String> files, List<DependencyView> dependencies, List<String> unversioned,
                                   boolean checked, String error) {

        public List<DependencyView> vulnerable() {
            return dependencies.stream().filter(d -> !d.vulnerabilities().isEmpty()).toList();
        }
    }

    /** 지금 저장소를 검사하고 기록으로 남긴 뒤, 지난 검사와 비교한다 */
    public ScanView scan(Long projectId) {
        Project project = project(projectId);
        DependencyReport report = check(Path.of(project.getRepoPath()));
        DependencyScan saved = scanRepository.save(new DependencyScan(projectId, project.currentCommit(), report.checked(),
                report.error(), report.dependencies().size(), report.vulnerable().size(),
                report.vulnerable().stream().mapToInt(d -> d.vulnerabilities().size()).sum(),
                jsonMapper.writeValueAsString(report)));
        return view(saved, report);
    }

    /** 검사 기록 (최근 것부터) */
    public List<ScanSummary> history(Long projectId) {
        project(projectId);
        return scanRepository.findByProjectIdOrderByIdDesc(projectId, Limit.of(HISTORY_LIMIT)).stream()
                .map(ScanSummary::from).toList();
    }

    /** 저장한 검사 하나와 그 앞 검사와의 비교 */
    public ScanView get(Long projectId, Long scanId) {
        DependencyScan scan = scanRepository.findById(scanId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElseThrow(() -> new NoSuchElementException("이 프로젝트의 라이브러리 검사 기록이 아님: " + scanId));
        return view(scan, read(scan));
    }

    private ScanView view(DependencyScan scan, DependencyReport report) {
        DependencyDiff diff = scanRepository.findFirstByProjectIdAndIdLessThanOrderByIdDesc(scan.getProjectId(), scan.getId())
                .map(previous -> DependencyDiff.between(previous, read(previous), report))
                .orElse(null);
        return new ScanView(scan.getId(), scan.getProjectId(), scan.getCommitSha(), scan.getCreatedAt(), report, diff);
    }

    private DependencyReport read(DependencyScan scan) {
        return jsonMapper.readValue(scan.getReport(), DependencyReport.class);
    }

    private Project project(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
    }

    public DependencyReport check(Path repoRoot) {
        ScanResult scan = DependencyScanner.scan(repoRoot);
        List<Dependency> dependencies = scan.dependencies();
        if (!properties.osvEnabled() || dependencies.isEmpty()) {
            return report(scan, Map.of(), false, properties.osvEnabled() ? null : "OSV 검사가 꺼져 있음 (codeagent.dependencies.osv-enabled)");
        }
        List<Dependency> asked = dependencies.stream().limit(properties.maxDependencies()).toList();
        try {
            String note = asked.size() < dependencies.size()
                    ? "라이브러리가 많아 앞의 " + asked.size() + "개만 검사함" : null;
            return report(scan, osvClient.query(asked), true, note);
        } catch (OsvClient.OsvException e) {
            return report(scan, Map.of(), false, e.getMessage());
        }
    }

    private static DependencyReport report(ScanResult scan, Map<Dependency, List<Vulnerability>> vulns, boolean checked, String error) {
        List<DependencyView> views = scan.dependencies().stream()
                .map(d -> new DependencyView(d.group(), d.artifact(), d.version(), d.file(), d.line(), d.scope(),
                        vulns.getOrDefault(d, List.of()).stream().sorted(Comparator.comparingInt(v -> rank(v.severity()))).toList()))
                // 취약점이 있는 것, 심각한 것부터
                .sorted(Comparator.comparingInt((DependencyView v) -> v.vulnerabilities().isEmpty() ? 1 : 0)
                        .thenComparingInt(v -> v.vulnerabilities().isEmpty() ? 99 : rank(v.vulnerabilities().getFirst().severity())))
                .toList();
        return new DependencyReport(scan.files(), views, scan.unversioned(), checked, error);
    }

    private static int rank(String severity) {
        int i = SEVERITY_ORDER.indexOf(severity);
        return i < 0 ? SEVERITY_ORDER.size() : i;
    }

    /** 계획 담당에게 보여줄 요약. 지난 검사가 있으면 달라진 점도 붙인다 */
    public static String summarize(ScanView scan) {
        String summary = summarize(scan.report());
        DependencyDiff diff = scan.diff();
        if (diff == null) {
            return summary + "\n지난 검사 기록 없음 (이번이 첫 검사)";
        }
        String previousCommit = diff.previousCommitSha() == null ? ""
                : ", 커밋 " + diff.previousCommitSha().substring(0, Math.min(12, diff.previousCommitSha().length()));
        StringBuilder out = new StringBuilder(summary).append("\n\n지난 검사(").append(diff.previousScannedAt().toLocalDate())
                .append(previousCommit).append(")와 비교: ");
        if (!diff.hasChanges()) {
            return out.append("달라진 것 없음").toString();
        }
        out.append('\n');
        diff.changed().forEach(c -> out.append("- 버전 변경 ").append(c.group()).append(':').append(c.artifact())
                .append(' ').append(c.from()).append(" → ").append(c.to()).append('\n'));
        diff.added().forEach(a -> out.append("- 추가 ").append(a).append('\n'));
        diff.removed().forEach(r -> out.append("- 제거 ").append(r).append('\n'));
        diff.newVulnerabilities().forEach(v -> out.append("- 새 취약점 ").append(v.displayId()).append(" [")
                .append(v.severity() == null ? "심각도 모름" : v.severity()).append("] ").append(v.group()).append(':')
                .append(v.artifact()).append(':').append(v.version()).append('\n'));
        diff.resolvedVulnerabilities().forEach(v -> out.append("- 해결된 취약점 ").append(v.displayId()).append(' ')
                .append(v.group()).append(':').append(v.artifact()).append('\n'));
        return out.toString().stripTrailing();
    }

    /** 계획 담당에게 보여줄 요약 */
    public static String summarize(DependencyReport report) {
        if (report.files().isEmpty()) {
            return "빌드 파일(pom.xml, build.gradle)을 찾지 못함";
        }
        StringBuilder out = new StringBuilder();
        out.append("빌드 파일: ").append(String.join(", ", report.files())).append('\n');
        out.append("라이브러리 ").append(report.dependencies().size()).append("개");
        if (!report.checked()) {
            out.append(" (알려진 취약점 검사 못 함: ").append(report.error()).append(")\n");
        } else {
            List<DependencyView> vulnerable = report.vulnerable();
            out.append(", 알려진 취약점이 있는 라이브러리 ").append(vulnerable.size()).append("개\n");
            for (DependencyView d : vulnerable) {
                out.append("- ").append(d.group()).append(':').append(d.artifact()).append(':').append(d.version())
                        .append(" (").append(d.file()).append(d.line() > 0 ? ":" + d.line() : "").append(", ").append(d.scope()).append(")\n");
                for (Vulnerability v : d.vulnerabilities().stream().limit(5).toList()) {
                    out.append("    · ").append(v.displayId());
                    if (!v.displayId().equals(v.id())) {
                        out.append(" / ").append(v.id());
                    }
                    out.append(" [").append(v.severity() == null ? "심각도 모름" : v.severity()).append("] ")
                            .append(v.summary() == null ? "" : v.summary());
                    if (!v.fixed().isEmpty()) {
                        out.append(" · 고친 버전 ").append(String.join(", ", v.fixed()));
                    }
                    out.append('\n');
                }
                if (d.vulnerabilities().size() > 5) {
                    out.append("    · 외 ").append(d.vulnerabilities().size() - 5).append("개\n");
                }
            }
            if (report.error() != null) {
                out.append("참고: ").append(report.error()).append('\n');
            }
        }
        if (!report.unversioned().isEmpty()) {
            out.append("버전을 알 수 없어 검사하지 못한 라이브러리 ").append(report.unversioned().size()).append("개: ")
                    .append(report.unversioned().stream().limit(15).collect(Collectors.joining(", ")))
                    .append(report.unversioned().size() > 15 ? " …" : "").append('\n');
        }
        out.append("전이 의존성은 gradle.lockfile 이 있을 때만 검사됨");
        return out.toString();
    }
}
