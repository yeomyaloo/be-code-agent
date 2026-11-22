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
 * XXE, XSS, 약한 암호화·난수 위험 지점.
 */
class OwaspSinkTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/owasp-app");

    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() {
        JavaProject project = JavaProject.load(FIXTURE);
        assertThat(project.parseErrors()).isEmpty();
        draft = new CodeGraphBuilder(project).build();
    }

    @Test
    void XXE_XSS_약한_암호화_위험_지점을_잡는다() {
        assertThat(draft.nodes().stream().filter(n -> n.kind() == CodeNodeKind.SINK).toList())
                .extracting(n -> n.props().get("api") + " " + n.props().get("category") + " " + n.props().get("cwe"))
                .containsExactlyInAnyOrder(
                        "javax.xml.parsers.DocumentBuilder.parse XXE CWE-611",
                        "org.dom4j.io.SAXReader.read XXE CWE-611",
                        "jakarta.servlet.http.HttpServletResponse.getWriter XSS CWE-79",
                        "java.util.Random.<init> WEAK_RANDOM CWE-330",
                        "java.security.MessageDigest.getInstance WEAK_CRYPTO CWE-328",
                        "javax.crypto.Cipher.getInstance WEAK_CRYPTO CWE-327");
    }

    @Test
    void 진입점에서_새_위험_지점까지_도달한다() {
        assertThat(reachableCategories("POST", "/api/xml/import")).containsExactly("XXE");
        assertThat(reachableCategories("GET", "/api/hello")).containsExactly("XSS");
        assertThat(reachableCategories("GET", "/api/password-reset")).containsExactly("WEAK_RANDOM");
    }

    @Test
    void 위험한_분류부터_조사한다() {
        assertThat(SinkRules.priority("COMMAND")).isLessThan(SinkRules.priority("XXE"));
        assertThat(SinkRules.priority("XXE")).isLessThan(SinkRules.priority("XSS"));
        assertThat(SinkRules.priority("XSS")).isLessThan(SinkRules.priority("WEAK_RANDOM"));
        assertThat(SinkRules.priority("모르는 분류")).isGreaterThan(SinkRules.priority("WEAK_RANDOM"));
    }

    private static List<String> reachableCategories(String httpMethod, String path) {
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
                .map(n -> n.props().get("category").toString())
                .distinct().toList();
    }
}
