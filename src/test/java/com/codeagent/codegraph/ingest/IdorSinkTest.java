package com.codeagent.codegraph.ingest;

import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;
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

/**
 * Spring Data 저장소의 id 접근(findById, deleteById 등)을 IDOR 후보 위험 지점으로 잡는다.
 */
class IdorSinkTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/idor-app");

    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() {
        JavaProject project = JavaProject.load(FIXTURE);
        assertThat(project.parseErrors()).isEmpty();
        draft = new CodeGraphBuilder(project).build();
    }

    @Test
    void 저장소의_id_접근_호출을_IDOR_위험_지점으로_잡는다() {
        assertThat(sinks())
                .extracting(n -> n.props().get("api") + " " + n.props().get("category") + " " + n.props().get("cwe"))
                .containsExactlyInAnyOrder(
                        "com.example.idor.post.PostRepository.findById IDOR CWE-639",
                        "com.example.idor.post.PostRepository.deleteById IDOR CWE-639",
                        // 프로젝트 안의 상위 저장소(BaseRepository)를 거쳐 JpaRepository 를 상속해도 잡는다
                        "com.example.idor.comment.CommentRepository.deleteById IDOR CWE-639");
    }

    @Test
    void 주인_조건이_붙은_조회는_위험_지점이_아니다() {
        assertThat(sinks()).noneMatch(n -> n.props().get("api").toString().contains("findByIdAndWriterId"));
        assertThat(reachableSinks("GET", "/api/posts/mine/{id}")).isEmpty();
    }

    @Test
    void 진입점에서_서비스를_거쳐_IDOR_위험_지점까지_도달한다() {
        assertThat(reachableSinks("GET", "/api/posts/{id}")).containsExactly("com.example.idor.post.PostRepository.findById");
        assertThat(reachableSinks("DELETE", "/api/posts/{id}")).containsExactly("com.example.idor.post.PostRepository.deleteById");
        assertThat(reachableSinks("DELETE", "/api/comments/{id}")).containsExactly("com.example.idor.comment.CommentRepository.deleteById");
    }

    private static List<NodeDraft> sinks() {
        return draft.nodes().stream().filter(n -> n.kind() == CodeNodeKind.SINK).toList();
    }

    private static List<String> reachableSinks(String httpMethod, String path) {
        String entry = draft.nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT
                        && httpMethod.equals(n.props().get("httpMethod")) && path.equals(n.props().get("path")))
                .findFirst().orElseThrow().key();
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
                .map(n -> n.props().get("api").toString())
                .toList();
    }
}
