package com.codeagent.report;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.analysisgraph.domain.ReviewDecision;
import com.codeagent.api.AnalysisController;
import com.codeagent.api.AnalysisController.ReviewRequest;
import com.codeagent.codegraph.ingest.CodeGraphIndexer;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkView;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import com.codeagent.report.ReportService.Report;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발견을 직접 만들어서 보고서(SARIF, HTML)와 사람 검토 API를 확인한다. 로컬 PostgreSQL이 필요하다.
 */
@SpringBootTest
class ReportTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app").toAbsolutePath();

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private CodeGraphIndexer codeGraphIndexer;
    @Autowired
    private CodeGraphQuery codeGraphQuery;
    @Autowired
    private AnalysisGraphService analysisGraph;
    @Autowired
    private ReportService reportService;
    @Autowired
    private AnalysisController analysisController;
    @Autowired
    private JsonMapper jsonMapper;

    private AnalysisJob job;
    private AnalysisNode confirmed;
    private AnalysisNode open;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(new Project("report-test", FIXTURE.toString(), "java"));
        codeGraphIndexer.index(project.getId());
        EntryPointView search = codeGraphQuery.entryPoints(project.getId()).stream()
                .filter(e -> e.path().equals("/api/users/search")).findFirst().orElseThrow();
        SinkView sql = codeGraphQuery.sinks(project.getId()).stream()
                .filter(s -> s.category().equals("SQL")).findFirst().orElseThrow();
        job = jobRepository.save(new AnalysisJob(project, 1_000_000L));

        confirmed = finding("사용자 검색 API의 SQL 인젝션", "CWE-89", "high", Map.of(
                "sourceNodeId", search.id(), "sinkNodeId", sql.id(),
                "exploitScenario", "GET /api/users/search?name=' OR '1'='1",
                "evidence", List.of(
                        Map.of("file", ".\\src\\main\\java\\com\\example\\vuln\\service\\UserServiceImpl.java", "line", 25, "note", "쿼리 실행"),
                        Map.of("file", "src/main/java/com/example/vuln/web/UserController.java", "line", 31, "note", "입력"))));
        analysisGraph.recordVerdict(job.getId(), confirmed.getId(), AnalysisNodeStatus.CONFIRMED,
                Map.of("verdict", "confirmed", "reasoning", "문자열을 이어 붙임", "severity", "critical"));

        open = finding("<script>alert(1)</script> 경로 조작", "CWE-22", "medium", Map.of(
                "evidence", List.of(Map.of("file", "src/main/java/com/example/vuln/service/FileService.java", "line", 12, "note", "<b>파일</b>"))));

        AnalysisNode rejected = finding("오탐인 명령 실행", "CWE-78", "high", Map.of(
                "evidence", List.of(Map.of("file", "CommandService.java", "line", 11, "note", "상수"))));
        analysisGraph.recordVerdict(job.getId(), rejected.getId(), AnalysisNodeStatus.REJECTED,
                Map.of("verdict", "rejected", "reasoning", "입력이 상수"));
    }

    @Test
    void 오탐을_빼고_심각도_순으로_모은다() {
        Report report = reportService.build(job.getId(), false);

        assertThat(report.findings()).extracting(ReportService.ReportFinding::id)
                .containsExactly(confirmed.getId(), open.getId());
        assertThat(report.dismissedCount()).isEqualTo(1);
        assertThat(report.findings().getFirst().severity()).as("검증 담당이 다시 매긴 심각도").isEqualTo("critical");
        assertThat(report.findings().getFirst().entryPoint()).isEqualTo("GET /api/users/search");
        assertThat(report.findings().getFirst().sink()).isEqualTo("org.springframework.jdbc.core.JdbcTemplate.queryForList");
        assertThat(reportService.build(job.getId(), true).findings()).hasSize(3);
    }

    @Test
    void SARIF_보고서를_만든다() {
        ResponseEntity<String> response = analysisController.sarifReport(job.getId(), false);
        JsonNode sarif = jsonMapper.readTree(response.getBody());

        assertThat(response.getHeaders().getContentType()).hasToString("application/sarif+json");
        assertThat(sarif.get("version").asString()).isEqualTo("2.1.0");
        JsonNode run = sarif.get("runs").get(0);
        assertThat(run.get("tool").get("driver").get("rules")).hasSize(2);
        assertThat(run.get("tool").get("driver").get("rules").get(0).get("helpUri").asString())
                .isEqualTo("https://cwe.mitre.org/data/definitions/89.html");

        JsonNode result = run.get("results").get(0);
        assertThat(result.get("ruleId").asString()).isEqualTo("CWE-89");
        assertThat(result.get("level").asString()).isEqualTo("error");
        assertThat(result.get("properties").get("security-severity").asString()).isEqualTo("9.5");
        assertThat(result.get("locations").get(0).get("physicalLocation").get("artifactLocation").get("uri").asString())
                .as("경로를 저장소 기준 슬래시로 정리").isEqualTo("src/main/java/com/example/vuln/service/UserServiceImpl.java");
        assertThat(result.get("relatedLocations")).hasSize(1);
        assertThat(run.get("results").get(1).get("level").asString()).isEqualTo("warning");
    }

    @Test
    void HTML_보고서는_내용을_이스케이프하고_스크립트를_막는다() {
        ResponseEntity<String> response = analysisController.htmlReport(job.getId(), false);
        String html = response.getBody();

        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "&lt;b&gt;파일&lt;/b&gt;", "SQL 인젝션", "확정")
                .doesNotContain("<script>alert(1)", "<b>파일");
    }

    @Test
    void 사람이_오탐으로_표시하면_보고서에서_빠진다() {
        analysisController.review(job.getId(), open.getId(), new ReviewRequest(ReviewDecision.FALSE_POSITIVE, "테스트 코드임"));

        Report report = reportService.build(job.getId(), false);
        assertThat(report.findings()).extracting(ReportService.ReportFinding::id).containsExactly(confirmed.getId());
        assertThat(report.dismissedCount()).isEqualTo(2);
        assertThat(reportService.build(job.getId(), true).findings())
                .filteredOn(f -> f.id().equals(open.getId()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.reviewDecision()).isEqualTo("FALSE_POSITIVE");
                    assertThat(f.reviewComment()).isEqualTo("테스트 코드임");
                    assertThat(f.status()).as("검증 판정은 그대로").isEqualTo("OPEN");
                });
    }

    @Test
    void 다른_작업의_발견은_검토할_수_없다() {
        assertThatThrownBy(() -> analysisController.review(job.getId() + 1000, open.getId(),
                new ReviewRequest(ReviewDecision.ACCEPTED, null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private AnalysisNode finding(String title, String cwe, String severity, Map<String, Object> extra) {
        Map<String, Object> props = new java.util.LinkedHashMap<>(extra);
        props.put("cwe", cwe);
        props.put("severity", severity);
        props.put("confidence", "high");
        return analysisGraph.addNode(job.getId(), AnalysisNodeKind.FINDING, title, title + " 설명", props);
    }
}
