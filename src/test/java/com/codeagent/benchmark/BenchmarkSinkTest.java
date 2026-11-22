package com.codeagent.benchmark;

import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;
import com.codeagent.codegraph.ingest.CodeGraphBuilder;
import com.codeagent.codegraph.ingest.CodeGraphDraft;
import com.codeagent.codegraph.ingest.JavaProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가 벤치마크(benchmark/)의 정해진 부분. LLM 없이 코드 그래프만 본다.
 * 정답 파일의 취약점과 함정이 모두 위험 지점으로 잡히고(조사 대상에 오르고), 진입점에서 이어지는지 확인한다.
 * LLM 이 취약점을 찾을 수 있는지는 benchmark/run.py 와 score.py 로 따로 잰다.
 */
class BenchmarkSinkTest {

    private static final Path APP = Path.of("benchmark/shop-app");
    private static final Path EXPECTED = Path.of("benchmark/shop-app.expected.json");

    private static CodeGraphDraft draft;
    private static JsonNode expected;

    @BeforeAll
    static void build() throws Exception {
        JavaProject project = JavaProject.load(APP);
        assertThat(project.parseErrors()).isEmpty();
        draft = new CodeGraphBuilder(project).build();
        expected = JsonMapper.builder().build().readTree(Files.readString(EXPECTED));
    }

    @Test
    void 정답의_취약점과_함정이_모두_위험_지점으로_잡히고_다른_것은_없다() {
        List<String> answer = new ArrayList<>();
        for (JsonNode item : items()) {
            answer.add(item.path("file").asString() + ":" + item.path("sinkLine").asInt() + " " + item.path("category").asString());
        }
        List<String> sinks = draft.nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.SINK)
                .map(n -> n.filePath() + ":" + n.startLine() + " " + n.props().get("category"))
                .toList();

        assertThat(sinks).containsExactlyInAnyOrderElementsOf(answer);
    }

    @Test
    void 정답의_취약점은_적힌_진입점에서_이어진다() {
        for (JsonNode vuln : expected.path("vulnerabilities")) {
            if (vuln.path("entryPoint").isNull()) {
                continue;
            }
            String[] entry = vuln.path("entryPoint").asString().split(" ", 2);
            String sink = vuln.path("file").asString() + ":" + vuln.path("sinkLine").asInt();
            assertThat(reachableSinks(entry[0], entry[1])).as(vuln.path("id").asString()).contains(sink);
        }
    }

    @Test
    void 정답_파일이_올바르다() {
        Set<String> ids = new HashSet<>();
        for (JsonNode item : items()) {
            String id = item.path("id").asString();
            assertThat(ids.add(id)).as("id 중복: " + id).isTrue();
            assertThat(Files.isRegularFile(APP.resolve(item.path("file").asString()))).as(id).isTrue();
            int start = item.path("lines").get(0).asInt();
            int end = item.path("lines").get(1).asInt();
            assertThat(item.path("sinkLine").asInt()).as(id).isBetween(start, end);
        }
        for (JsonNode vuln : expected.path("vulnerabilities")) {
            assertThat(vuln.path("cwe").isArray() && !vuln.path("cwe").isEmpty()).as(vuln.path("id").asString()).isTrue();
        }
        // 분석 대상 폴더 안에 정답이나 힌트가 없어야 LLM 이 답을 베끼지 못한다
        assertThat(APP.resolve("expected.json")).doesNotExist();
    }

    private static List<JsonNode> items() {
        List<JsonNode> items = new ArrayList<>();
        expected.path("vulnerabilities").forEach(items::add);
        expected.path("safe").forEach(items::add);
        return items;
    }

    private static List<String> reachableSinks(String httpMethod, String path) {
        String entry = draft.nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT
                        && httpMethod.equals(n.props().get("httpMethod")) && path.equals(n.props().get("path")))
                .findFirst().orElseThrow(() -> new AssertionError("진입점 없음: " + httpMethod + " " + path)).key();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(entry));
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!visited.add(key)) {
                continue;
            }
            draft.edges().stream()
                    .filter(e -> e.srcKey().equals(key) && e.kind() != CodeEdgeKind.CONTAINS)
                    .forEach(e -> queue.add(e.dstKey()));
        }
        return visited.stream().map(draft::node)
                .filter(n -> n.kind() == CodeNodeKind.SINK)
                .map(n -> n.filePath() + ":" + n.startLine())
                .toList();
    }
}
