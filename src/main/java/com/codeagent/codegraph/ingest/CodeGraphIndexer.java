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

    /**
     * @param removedNodes 이번 파싱 결과에서 사라져 지운 노드 수
     * @param retainedNodes 사라졌지만 분석 그래프(anchor)가 참조하고 있어 removed_at 표시만 하고 남긴 노드 수
     */
    public record IndexResult(Long projectId, long files, long classes, long methods, long entryPoints, long sinks,
                              long callEdges, long overrideEdges, int unresolvedCalls, int removedNodes,
                              int retainedNodes, List<String> parseErrors, long elapsedMs) {
    }

    /**
     * 코드 그래프를 다시 만든다. 노드는 (종류, 정규화된 이름)이 같으면 id를 유지하므로
     * 분석 그래프의 연결점(anchor)이 그대로 이어진다. 연결선은 매번 새로 만든다.
     */
    @Transactional
    public IndexResult index(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
        long start = System.currentTimeMillis();

        JavaProject javaProject = JavaProject.load(Path.of(project.getRepoPath()));
        CodeGraphDraft draft = new CodeGraphBuilder(javaProject).build();

        jdbcTemplate.update("delete from code_edge where project_id = ?", projectId);
        upsertNodes(projectId, List.copyOf(draft.nodes()));

        // indexed_at 은 트랜잭션 시작 시각(now())으로 찍히므로, 그보다 이전이면 이번 파싱 결과에 없는 노드다
        int removed = jdbcTemplate.update("""
                delete from code_node n
                where n.project_id = ? and n.indexed_at < now()
                  and not exists (select 1 from anchor a where a.code_node_id = n.id)
                """, projectId);
        int retained = jdbcTemplate.update("""
                update code_node set removed_at = coalesce(removed_at, now())
                where project_id = ? and indexed_at < now()
                """, projectId);

        insertEdges(projectId, List.copyOf(draft.edges()), liveNodeIds(projectId));

        IndexResult result = new IndexResult(projectId,
                draft.count(CodeNodeKind.FILE), draft.count(CodeNodeKind.CLASS), draft.count(CodeNodeKind.METHOD),
                draft.count(CodeNodeKind.ENTRY_POINT), draft.count(CodeNodeKind.SINK),
                draft.count(CodeEdgeKind.CALLS), draft.count(CodeEdgeKind.OVERRIDDEN_BY),
                draft.unresolvedCalls(), removed, retained, javaProject.parseErrors(),
                System.currentTimeMillis() - start);
        log.info("코드 그래프 생성 완료: {}", result);
        return result;
    }

    private void upsertNodes(Long projectId, List<NodeDraft> nodes) {
        jdbcTemplate.batchUpdate("""
                        insert into code_node (project_id, kind, qualified_name, file_path, start_line, end_line, props,
                                               indexed_at, removed_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, now(), null)
                        on conflict (project_id, kind, qualified_name) do update set
                            file_path = excluded.file_path,
                            start_line = excluded.start_line,
                            end_line = excluded.end_line,
                            props = excluded.props,
                            indexed_at = excluded.indexed_at,
                            removed_at = null
                        """, nodes, BATCH_SIZE, (ps, node) -> {
                    ps.setLong(1, projectId);
                    ps.setString(2, node.kind().name());
                    ps.setString(3, node.qualifiedName());
                    ps.setString(4, node.filePath());
                    ps.setObject(5, node.startLine(), Types.INTEGER);
                    ps.setObject(6, node.endLine(), Types.INTEGER);
                    ps.setString(7, jsonMapper.writeValueAsString(node.props()));
                });
    }

    private Map<String, Long> liveNodeIds(Long projectId) {
        Map<String, Long> ids = new HashMap<>();
        jdbcTemplate.query("select id, kind, qualified_name from code_node where project_id = ? and removed_at is null",
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
