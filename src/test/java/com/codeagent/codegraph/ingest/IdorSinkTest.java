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
        assertThat(sinks().stream().filter(n -> !n.props().get("api").toString().contains(".order.")).toList())
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

    @Test
    void 저장소_매퍼에_선언한_id_조회와_요청_본문_저장도_IDOR_후보로_잡는다() {
        assertThat(sinks()).filteredOn(n -> n.props().get("api").toString().contains(".order."))
                .extracting(n -> n.props().get("api") + " " + n.props().get("category"))
                .containsExactlyInAnyOrder(
                        // MyBatis 매퍼: id 하나만 받는 메서드
                        "com.example.idor.order.OrderMapper.selectById IDOR",
                        "com.example.idor.order.OrderMapper.deleteOrder IDOR",
                        // 직접 쓴 @Query 에 id 하나만 받음, 조건이 식별자 하나뿐인 파생 쿼리
                        "com.example.idor.order.OrderRepository.findDetail IDOR",
                        "com.example.idor.order.OrderRepository.findByBuyerId IDOR",
                        // @RequestBody 로 받은 객체를 그대로 저장
                        "com.example.idor.order.OrderRepository.save IDOR");
        assertThat(reachableSinks("GET", "/api/orders/{id}")).containsExactly("com.example.idor.order.OrderMapper.selectById");
        assertThat(reachableSinks("PUT", "/api/orders")).containsExactly("com.example.idor.order.OrderRepository.save");
        // 주인 조건이 같이 있거나 식별자가 아닌 값으로 찾으면 후보가 아니다
        assertThat(reachableSinks("GET", "/api/orders/mine/{id}")).isEmpty();
        assertThat(reachableSinks("GET", "/api/orders/search")).isEmpty();
    }

    @Test
    void 식별자_조건_하나뿐인_파생_쿼리만_후보다() {
        assertThat(SinkRules.singleIdCondition("findByOrderId")).isTrue();
        assertThat(SinkRules.singleIdCondition("deleteByBuyerIdIn")).isTrue();
        assertThat(SinkRules.singleIdCondition("findByIdOrderByCreatedAtDesc")).isTrue();
        assertThat(SinkRules.singleIdCondition("findByIdAndWriterId")).isFalse();
        assertThat(SinkRules.singleIdCondition("findByOrderNumber")).isFalse();
        assertThat(SinkRules.singleIdCondition("findByOrderStatus")).isFalse();
        assertThat(SinkRules.idLikeName("orderId")).isTrue();
        assertThat(SinkRules.idLikeName("valid")).isFalse();
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
