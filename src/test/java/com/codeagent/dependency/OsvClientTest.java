package com.codeagent.dependency;

import com.codeagent.dependency.DependencyService.DependencyReport;
import com.codeagent.project.ProjectRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSV 응답을 흉내 내는 로컬 HTTP 서버로 취약점 조회와 요약을 확인한다. 실제 OSV 에는 요청하지 않는다.
 */
class OsvClientTest {

    private HttpServer server;
    private final AtomicReference<String> batchBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/querybatch", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            batchBody.set(body);
            // 질문 순서대로 답한다. snakeyaml 만 취약점이 하나 있다
            StringBuilder results = new StringBuilder();
            for (var query : JsonMapper.builder().build().readTree(body).path("queries")) {
                results.append(results.isEmpty() ? "" : ",").append("org.yaml:snakeyaml".equals(query.path("package").path("name").asString())
                        ? "{\"vulns\":[{\"id\":\"GHSA-mjmj-j48q-9wg2\"}]}" : "{}");
            }
            respond(exchange, "{\"results\":[" + results + "]}");
        });
        server.createContext("/v1/vulns/GHSA-mjmj-j48q-9wg2", exchange -> respond(exchange, """
                {"id":"GHSA-mjmj-j48q-9wg2","summary":"SnakeYaml Constructor Deserialization Remote Code Execution",
                 "aliases":["CVE-2022-1471"],
                 "affected":[{"package":{"ecosystem":"Maven","name":"org.yaml:snakeyaml"},
                              "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.0"}]}]}],
                 "database_specific":{"severity":"HIGH","cwe_ids":["CWE-502"]}}
                """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 라이브러리마다_알려진_취약점과_고친_버전을_붙인다() {
        OsvClient client = new OsvClient(properties(true), JsonMapper.builder().build());
        Dependency yaml = new Dependency("org.yaml", "snakeyaml", "1.29", "pom.xml", 20, "dependency");
        Dependency junit = new Dependency("junit", "junit", "4.12", "pom.xml", 30, "test");

        Map<Dependency, List<Vulnerability>> result = client.query(List.of(junit, yaml));

        assertThat(batchBody.get()).contains("\"ecosystem\":\"Maven\"", "\"name\":\"org.yaml:snakeyaml\"", "\"version\":\"1.29\"");
        assertThat(result.get(junit)).isEmpty();
        Vulnerability vuln = result.get(yaml).getFirst();
        assertThat(vuln.displayId()).isEqualTo("CVE-2022-1471");
        assertThat(vuln.severity()).isEqualTo("high");
        assertThat(vuln.fixed()).containsExactly("2.0");
        assertThat(vuln.cwes()).containsExactly("CWE-502");
        assertThat(vuln.url()).isEqualTo("https://osv.dev/vulnerability/GHSA-mjmj-j48q-9wg2");
    }

    @Test
    void 계획_담당에게_취약한_라이브러리를_요약한다() {
        DependencyService service = new DependencyService(Mockito.mock(ProjectRepository.class),
                new OsvClient(properties(true), JsonMapper.builder().build()), properties(true),
                null, JsonMapper.builder().build());

        DependencyReport report = service.check(Path.of("src/test/resources/fixtures/deps-maven"));

        assertThat(report.checked()).isTrue();
        assertThat(report.vulnerable()).extracting(DependencyService.DependencyView::artifact).containsExactly("snakeyaml");
        String summary = DependencyService.summarize(report);
        assertThat(summary).contains("빌드 파일: pom.xml", "알려진 취약점이 있는 라이브러리 1개", "CVE-2022-1471",
                "[high]", "고친 버전 2.0", "버전을 알 수 없어 검사하지 못한 라이브러리 1개");
    }

    @Test
    void OSV_에_연결하지_못하면_목록만_돌려주고_이유를_알린다() {
        server.stop(0);
        DependencyService service = new DependencyService(Mockito.mock(ProjectRepository.class),
                new OsvClient(properties(true), JsonMapper.builder().build()), properties(true),
                null, JsonMapper.builder().build());

        DependencyReport report = service.check(Path.of("src/test/resources/fixtures/deps-maven"));

        assertThat(report.checked()).isFalse();
        assertThat(report.error()).contains("OSV");
        assertThat(report.dependencies()).hasSize(4).allMatch(d -> d.vulnerabilities().isEmpty());
    }

    @Test
    void 검사를_끄면_OSV_에_묻지_않는다() {
        DependencyService service = new DependencyService(Mockito.mock(ProjectRepository.class),
                new OsvClient(properties(false), JsonMapper.builder().build()), properties(false),
                null, JsonMapper.builder().build());

        DependencyReport report = service.check(Path.of("src/test/resources/fixtures/deps-maven"));

        assertThat(batchBody.get()).isNull();
        assertThat(report.checked()).isFalse();
        assertThat(report.error()).contains("꺼져 있음");
    }

    private DependencyProperties properties(boolean enabled) {
        return new DependencyProperties(enabled, "http://127.0.0.1:" + server.getAddress().getPort(), 5, 500);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
