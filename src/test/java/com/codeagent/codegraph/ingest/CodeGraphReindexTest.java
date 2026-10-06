package com.codeagent.codegraph.ingest;

import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnchorRole;
import com.codeagent.codegraph.ingest.CodeGraphIndexer.IndexResult;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkView;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분석 그래프가 코드 노드를 참조하고 있어도 코드 그래프를 다시 만들 수 있는지 확인한다.
 * 로컬 PostgreSQL(codeagent DB)이 필요하다.
 */
@SpringBootTest
class CodeGraphReindexTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app");
    private static final String SERVICE_DIR = "src/main/java/com/example/vuln/service/";
    private static final String FIND_BY_NAME = "com.example.vuln.service.UserServiceImpl#findByName(String)";

    @TempDir
    Path repo;

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private CodeGraphIndexer indexer;
    @Autowired
    private CodeGraphQuery query;
    @Autowired
    private AnalysisGraphService analysisGraph;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void setUp() throws IOException {
        copy(FIXTURE, repo);
        project = projectRepository.save(new Project("reindex-test", repo.toString(), "java"));
    }

    @Test
    void 바뀌지_않은_노드는_id를_유지하고_연결점이_있는_사라진_노드는_남긴다() throws IOException {
        indexer.index(project.getId());
        Long methodId = method(FIND_BY_NAME).id();
        SinkView sqlSink = sink("SQL");

        // 분석 그래프가 메서드와 SQL 위험 지점을 참조하게 한다
        AnalysisNode fact = factWithAnchors(List.of(methodId, sqlSink.id()));

        // 파일 맨 위에 줄을 넣으면 위험 지점의 줄 번호(= 이름)가 바뀐다
        Path impl = repo.resolve(SERVICE_DIR + "UserServiceImpl.java");
        Files.writeString(impl, "// 수정됨\n" + Files.readString(impl));
        IndexResult result = indexer.index(project.getId());

        assertThat(result.retainedNodes()).isEqualTo(1);
        assertThat(method(FIND_BY_NAME).id()).as("메서드 노드 id 유지").isEqualTo(methodId);
        assertThat(sink("SQL").id()).as("새 위험 지점은 새 노드").isNotEqualTo(sqlSink.id());
        assertThat(sink("SQL").line()).isEqualTo(sqlSink.line() + 1);
        assertThat(query.sinks(project.getId())).as("사라진 노드는 조회에서 빠짐")
                .noneMatch(s -> s.id().equals(sqlSink.id()));
        assertThat(removedAt(sqlSink.id())).as("연결점이 있는 사라진 노드는 표시만 하고 남김").isNotNull();
        assertThat(anchorCount(fact.getId())).isEqualTo(2);

        // 여전히 진입점에서 새 SQL 위험 지점까지 경로가 있다
        assertThat(query.sinkPaths(project.getId(), 12))
                .anyMatch(p -> p.sink().category().equals("SQL") && p.entryPoint().path().equals("/api/users/search"));
    }

    @Test
    void 연결점이_없는_사라진_노드는_지운다() throws IOException {
        indexer.index(project.getId());
        long before = nodeCount();

        Files.delete(repo.resolve(SERVICE_DIR + "CommandService.java"));
        IndexResult result = indexer.index(project.getId());

        assertThat(result.retainedNodes()).isZero();
        assertThat(result.removedNodes()).isPositive();
        assertThat(nodeCount()).isEqualTo(before - result.removedNodes());
        assertThat(query.sinks(project.getId())).noneMatch(s -> s.category().equals("COMMAND"));
        assertThat(query.findNodes(project.getId(), "com.example.vuln.service.CommandService", null, 10)).isEmpty();
    }

    @Test
    void 사라졌던_노드가_다시_나타나면_같은_id로_되살린다() throws IOException {
        indexer.index(project.getId());
        Path impl = repo.resolve(SERVICE_DIR + "UserServiceImpl.java");
        String original = Files.readString(impl);
        SinkView sqlSink = sink("SQL");
        factWithAnchors(List.of(sqlSink.id()));

        Files.writeString(impl, "// 수정됨\n" + original);
        indexer.index(project.getId());
        Files.writeString(impl, original);
        indexer.index(project.getId());

        assertThat(sink("SQL").id()).isEqualTo(sqlSink.id());
        assertThat(removedAt(sqlSink.id())).isNull();
    }

    private AnalysisNode factWithAnchors(List<Long> codeNodeIds) {
        AnalysisJob job = jobRepository.save(new AnalysisJob(project, 1000L));
        AnalysisNode fact = analysisGraph.addNode(job.getId(), AnalysisNodeKind.FACT, "테스트 사실", "", Map.of());
        analysisGraph.anchor(fact.getId(), codeNodeIds, AnchorRole.LOCATED_AT);
        return fact;
    }

    private CodeNodeView method(String qualifiedName) {
        return query.findNodes(project.getId(), qualifiedName, "METHOD", 2).stream()
                .filter(n -> n.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow();
    }

    private SinkView sink(String category) {
        return query.sinks(project.getId()).stream().filter(s -> s.category().equals(category)).findFirst().orElseThrow();
    }

    private Object removedAt(Long nodeId) {
        return jdbcTemplate.queryForObject("select removed_at from code_node where id = ?", Object.class, nodeId);
    }

    private int anchorCount(Long analysisNodeId) {
        return jdbcTemplate.queryForObject("select count(*) from anchor where analysis_node_id = ?", Integer.class, analysisNodeId);
    }

    private long nodeCount() {
        return jdbcTemplate.queryForObject("select count(*) from code_node where project_id = ?", Long.class, project.getId());
    }

    private static void copy(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
