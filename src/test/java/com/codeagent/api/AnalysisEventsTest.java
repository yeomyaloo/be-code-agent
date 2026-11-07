package com.codeagent.api;

import com.codeagent.analysisgraph.AnalysisEvents;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실시간 이벤트(SSE). 실제 HTTP 로 연결해서 분석 그래프가 바뀌면 change, 작업이 끝나면 finished 가 오는지 본다.
 * 로컬 PostgreSQL 이 필요하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisEventsTest {

    @LocalServerPort
    private int port;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private AnalysisGraphService analysisGraph;
    @Autowired
    private AnalysisEvents analysisEvents;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 진행_중인_작업은_변화와_종료를_실시간으로_알린다() throws Exception {
        AnalysisJob job = runningJob();
        List<String> lines = new ArrayList<>();
        CompletableFuture<Void> reading = readEvents(job.getId(), lines);

        waitUntil(() -> lines.contains("event:ready"));
        analysisGraph.addNode(job.getId(), AnalysisNodeKind.FACT, "사실", "", Map.of());
        waitUntil(() -> lines.contains("event:change") && lines.contains("data:node"));

        analysisEvents.finished(job.getId(), "DONE");
        // finished 를 보낸 뒤 서버가 연결을 닫으므로 읽기가 끝난다
        reading.get(10, TimeUnit.SECONDS);
        assertThat(lines).containsSubsequence("event:ready", "event:change", "event:finished", "data:DONE");
    }

    @Test
    void 다른_작업의_변화는_받지_않는다() throws Exception {
        AnalysisJob mine = runningJob();
        AnalysisJob other = runningJob();
        List<String> lines = new ArrayList<>();
        CompletableFuture<Void> reading = readEvents(mine.getId(), lines);

        waitUntil(() -> lines.contains("event:ready"));
        analysisGraph.addNode(other.getId(), AnalysisNodeKind.FACT, "다른 작업", "", Map.of());
        analysisEvents.finished(mine.getId(), "DONE");
        reading.get(10, TimeUnit.SECONDS);

        assertThat(lines).doesNotContain("event:change");
    }

    @Test
    void 이미_끝난_작업은_종료를_알리고_바로_닫는다() throws Exception {
        Project project = projectRepository.save(new Project("events-test", "/tmp/events-test", "java"));
        AnalysisJob done = jobRepository.save(new AnalysisJob(project, 100_000L));
        jobRepository.finish(done.getId(), com.codeagent.project.JobStatus.DONE, null, java.time.OffsetDateTime.now());

        List<String> lines = new ArrayList<>();
        readEvents(done.getId(), lines).get(10, TimeUnit.SECONDS);

        assertThat(lines).containsSubsequence("event:ready", "event:finished", "data:DONE");
    }

    private AnalysisJob runningJob() {
        Project project = projectRepository.save(new Project("events-test", "/tmp/events-test", "java"));
        AnalysisJob job = new AnalysisJob(project, 100_000L);
        job.start();
        return jobRepository.save(job);
    }

    /** 이벤트 스트림을 줄 단위로 읽어 lines 에 쌓는다. 서버가 연결을 닫으면 끝난다 */
    private CompletableFuture<Void> readEvents(Long jobId, List<String> lines) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/analyses/" + jobId + "/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(20))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenAccept(response -> {
            assertThat(response.statusCode()).isEqualTo(200);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (lines) {
                        lines.add(line.replace(": ", ":"));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("기다린 이벤트가 오지 않음");
    }
}
