package com.codeagent.codegraph.ingest;

import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;
import com.codeagent.codegraph.ingest.CodeGraphDraft.EdgeDraft;
import com.codeagent.codegraph.ingest.CodeGraphDraft.NodeDraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphBuilderTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app");
    private static final String SERVICE = "com.example.vuln.service.";

    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() {
        JavaProject project = JavaProject.load(FIXTURE);
        assertThat(project.parseErrors()).isEmpty();
        draft = new CodeGraphBuilder(project).build();
    }

    @Test
    void 구조_노드를_만든다() {
        assertThat(draft.count(CodeNodeKind.FILE)).isEqualTo(5);
        assertThat(draft.count(CodeNodeKind.CLASS)).isEqualTo(5);
        assertThat(draft.node(CodeGraphDraft.key(CodeNodeKind.METHOD, SERVICE + "UserServiceImpl#findByName(String)")))
                .isNotNull();
    }

    @Test
    void 컨트롤러_진입점을_찾는다() {
        assertThat(nodes(CodeNodeKind.ENTRY_POINT))
                .extracting(n -> n.props().get("httpMethod") + " " + n.props().get("path"))
                .containsExactlyInAnyOrder(
                        "GET /api/users/search",
                        "POST /api/users/ping",
                        "GET /api/users/files/{name}",
                        "HEAD /api/users/files/{name}",
                        "GET /api/users/health");
    }

    @Test
    void 라이브러리_jar_없이_위험_지점을_찾는다() {
        assertThat(nodes(CodeNodeKind.SINK))
                .extracting(n -> n.props().get("api"))
                .containsExactlyInAnyOrder(
                        "org.springframework.jdbc.core.JdbcTemplate.queryForList",
                        "java.lang.Runtime.exec",
                        "java.lang.ProcessBuilder.<init>",
                        "java.nio.file.Files.readAllBytes",
                        "java.nio.file.Paths.get",
                        "java.io.FileInputStream.<init>",
                        "java.io.ObjectInputStream.readObject");
    }

    @Test
    void 인터페이스_메서드에서_구현_메서드로_연결한다() {
        assertThat(draft.edges()).contains(new EdgeDraft(
                method(SERVICE + "UserService#search(String)"),
                method(SERVICE + "UserServiceImpl#search(String)"),
                CodeEdgeKind.OVERRIDDEN_BY));
    }

    @Test
    void 진입점에서_인터페이스를_거쳐_SQL_위험_지점까지_도달한다() {
        String entry = draft.nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT && "/api/users/search".equals(n.props().get("path")))
                .findFirst().orElseThrow().key();

        assertThat(reachable(entry))
                .filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.props().get("category"))
                .containsExactly("SQL");
    }

    @Test
    void 위험_지점이_없는_진입점은_아무데도_도달하지_않는다() {
        String entry = draft.nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT && "/api/users/health".equals(n.props().get("path")))
                .findFirst().orElseThrow().key();

        assertThat(reachable(entry)).noneMatch(n -> n.kind() == CodeNodeKind.SINK);
    }

    private static List<NodeDraft> nodes(CodeNodeKind kind) {
        return draft.nodes().stream().filter(n -> n.kind() == kind).toList();
    }

    private static String method(String key) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, key);
    }

    private static List<NodeDraft> reachable(String startKey) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(startKey));
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!visited.add(key)) {
                continue;
            }
            draft.edges().stream()
                    .filter(e -> e.srcKey().equals(key) && e.kind() != CodeEdgeKind.CONTAINS)
                    .forEach(e -> queue.add(e.dstKey()));
        }
        return visited.stream().map(draft::node).toList();
    }
}
