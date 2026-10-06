package com.codeagent.codegraph.ingest;

import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;
import com.codeagent.codegraph.ingest.CodeGraphDraft.EdgeDraft;
import com.codeagent.codegraph.ingest.CodeGraphDraft.NodeDraft;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 프로젝트 저장소를 파싱해서 코드 그래프를 DB에 (다시) 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeGraphIndexer {

    private static final int BATCH_SIZE = 500;

    private final ProjectRepository projectRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public record IndexResult(Long projectId, long files, long classes, long methods, long entryPoints, long sinks,
                              long callEdges, long overrideEdges, int unresolvedCalls, List<String> parseErrors,
                              long elapsedMs) {
    }

    @Transactional
    public IndexResult index(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
        long start = System.currentTimeMillis();

        JavaProject javaProject = JavaProject.load(Path.of(project.getRepoPath()));
        CodeGraphDraft draft = new CodeGraphBuilder(javaProject).build();

        // 다시 만들 때는 기존 그래프를 지운다 (연결점이 생긴 뒤에는 증분 갱신으로 바꿔야 함)
        jdbcTemplate.update("delete from code_edge where project_id = ?", projectId);
        jdbcTemplate.update("delete from code_node where project_id = ?", projectId);
        Map<String, Long> ids = insertNodes(projectId, List.copyOf(draft.nodes()));
        insertEdges(projectId, List.copyOf(draft.edges()), ids);

        IndexResult result = new IndexResult(projectId,
                draft.count(CodeNodeKind.FILE), draft.count(CodeNodeKind.CLASS), draft.count(CodeNodeKind.METHOD),
                draft.count(CodeNodeKind.ENTRY_POINT), draft.count(CodeNodeKind.SINK),
                draft.count(CodeEdgeKind.CALLS), draft.count(CodeEdgeKind.OVERRIDDEN_BY),
                draft.unresolvedCalls(), javaProject.parseErrors(), System.currentTimeMillis() - start);
        log.info("코드 그래프 생성 완료: {}", result);
        return result;
    }

    private Map<String, Long> insertNodes(Long projectId, List<NodeDraft> nodes) {
        jdbcTemplate.batchUpdate("""
                        insert into code_node (project_id, kind, qualified_name, file_path, start_line, end_line, props)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb)
                        """, nodes, BATCH_SIZE, (ps, node) -> {
                    ps.setLong(1, projectId);
                    ps.setString(2, node.kind().name());
                    ps.setString(3, node.qualifiedName());
                    ps.setString(4, node.filePath());
                    ps.setObject(5, node.startLine(), Types.INTEGER);
                    ps.setObject(6, node.endLine(), Types.INTEGER);
                    ps.setString(7, jsonMapper.writeValueAsString(node.props()));
                });

        Map<String, Long> ids = new HashMap<>();
        jdbcTemplate.query("select id, kind, qualified_name from code_node where project_id = ?",
                rs -> {
                    ids.put(CodeGraphDraft.key(CodeNodeKind.valueOf(rs.getString("kind")), rs.getString("qualified_name")),
                            rs.getLong("id"));
                }, projectId);
        return ids;
    }

    private void insertEdges(Long projectId, List<EdgeDraft> edges, Map<String, Long> ids) {
        List<EdgeDraft> valid = edges.stream()
                .filter(e -> ids.containsKey(e.srcKey()) && ids.containsKey(e.dstKey()))
                .toList();
        jdbcTemplate.batchUpdate("""
                        insert into code_edge (project_id, src_id, dst_id, kind) values (?, ?, ?, ?)
                        on conflict do nothing
                        """, valid, BATCH_SIZE, (ps, edge) -> {
                    ps.setLong(1, projectId);
                    ps.setLong(2, ids.get(edge.srcKey()));
                    ps.setLong(3, ids.get(edge.dstKey()));
                    ps.setString(4, edge.kind().name());
                });
    }
}
