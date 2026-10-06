package com.codeagent.codegraph.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 코드 그래프 조회. 그래프 탐색은 재귀 CTE로 DB에서 한다.
 */
@Repository
@RequiredArgsConstructor
public class CodeGraphQuery {

    private static final int MAX_PATHS = 500;

    private final JdbcTemplate jdbcTemplate;

    public record CodeNodeView(Long id, String kind, String qualifiedName, String filePath, Integer line) {
    }

    public record EntryPointView(Long id, String httpMethod, String path, String handler, String filePath, Integer line) {
    }

    public record SinkView(Long id, String category, String cwe, String api, String method, String snippet,
                           String filePath, Integer line) {
    }

    public record SinkPathView(EntryPointView entryPoint, SinkView sink, List<CodeNodeView> path) {
    }

    public List<EntryPointView> entryPoints(Long projectId) {
        return jdbcTemplate.query("""
                select id, props ->> 'httpMethod' as http_method, props ->> 'path' as path, props ->> 'handler' as handler,
                       file_path, start_line
                from code_node where project_id = ? and kind = 'ENTRY_POINT'
                order by props ->> 'path', props ->> 'httpMethod'
                """, (rs, i) -> entryPoint(rs), projectId);
    }

    public List<SinkView> sinks(Long projectId) {
        return jdbcTemplate.query("""
                select id, props ->> 'category' as category, props ->> 'cwe' as cwe, props ->> 'api' as api,
                       props ->> 'method' as method, props ->> 'snippet' as snippet, file_path, start_line
                from code_node where project_id = ? and kind = 'SINK'
                order by category, file_path, start_line
                """, (rs, i) -> sink(rs), projectId);
    }

    /** 이름 일부로 노드를 찾는다 (대소문자 무시). kind가 null이면 모든 종류 */
    public List<CodeNodeView> findNodes(Long projectId, String nameContains, String kind, int limit) {
        return jdbcTemplate.query("""
                select id, kind, qualified_name, file_path, start_line from code_node
                where project_id = ? and qualified_name ilike ? and (?::text is null or kind = ?)
                order by length(qualified_name), qualified_name
                limit ?
                """, (rs, i) -> codeNode(rs),
                projectId, "%" + escapeLike(nameContains) + "%", kind, kind, limit);
    }

    public java.util.Optional<CodeNodeView> node(Long projectId, Long nodeId) {
        return jdbcTemplate.query("""
                select id, kind, qualified_name, file_path, start_line from code_node where project_id = ? and id = ?
                """, (rs, i) -> codeNode(rs), projectId, nodeId).stream().findFirst();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 이 메서드를 호출하는 메서드 (상위 타입 메서드를 통한 호출 포함) */
    public List<CodeNodeView> callers(Long nodeId) {
        return jdbcTemplate.query("""
                select n.id, n.kind, n.qualified_name, n.file_path, n.start_line
                from code_edge e join code_node n on n.id = e.src_id
                where e.dst_id = ? and e.kind in ('CALLS', 'ROUTES_TO', 'OVERRIDDEN_BY')
                order by n.qualified_name
                """, (rs, i) -> codeNode(rs), nodeId);
    }

    /** 이 메서드(진입점)가 호출하는 메서드·위험 지점 (구현 메서드 포함) */
    public List<CodeNodeView> callees(Long nodeId) {
        return jdbcTemplate.query("""
                select n.id, n.kind, n.qualified_name, n.file_path, n.start_line
                from code_edge e join code_node n on n.id = e.dst_id
                where e.src_id = ? and e.kind in ('CALLS', 'OVERRIDDEN_BY', 'ROUTES_TO')
                order by n.qualified_name
                """, (rs, i) -> codeNode(rs), nodeId);
    }

    /** 진입점에서 위험 지점까지 이어지는 호출 경로 */
    public List<SinkPathView> sinkPaths(Long projectId, int maxDepth) {
        List<Long[]> paths = jdbcTemplate.query("""
                with recursive walk (node_id, path, depth) as (
                    select id, array[id], 0
                    from code_node where project_id = ? and kind = 'ENTRY_POINT'
                    union all
                    select e.dst_id, w.path || e.dst_id, w.depth + 1
                    from walk w
                    join code_edge e on e.src_id = w.node_id and e.kind in ('ROUTES_TO', 'CALLS', 'OVERRIDDEN_BY')
                    where w.depth < ? and not e.dst_id = any (w.path)
                )
                select w.path from walk w join code_node n on n.id = w.node_id
                where n.kind = 'SINK'
                order by array_length(w.path, 1)
                limit ?
                """, (rs, i) -> toLongArray(rs.getArray("path")), projectId, maxDepth, MAX_PATHS);
        if (paths.isEmpty()) {
            return List.of();
        }

        Set<Long> allIds = new LinkedHashSet<>();
        paths.forEach(p -> allIds.addAll(Arrays.asList(p)));
        Long[] idArray = allIds.toArray(Long[]::new);
        Map<Long, CodeNodeView> nodes = new HashMap<>();
        jdbcTemplate.query("""
                select id, kind, qualified_name, file_path, start_line from code_node where id = any (?)
                """, rs -> {
            CodeNodeView view = codeNode(rs);
            nodes.put(view.id(), view);
        }, (Object) idArray);

        Map<Long, EntryPointView> entryPoints = new HashMap<>();
        entryPoints(projectId).forEach(e -> entryPoints.put(e.id(), e));
        Map<Long, SinkView> sinks = new HashMap<>();
        sinks(projectId).forEach(s -> sinks.put(s.id(), s));

        return paths.stream()
                .map(p -> new SinkPathView(entryPoints.get(p[0]), sinks.get(p[p.length - 1]),
                        Arrays.stream(p).map(nodes::get).toList()))
                .toList();
    }

    private static Long[] toLongArray(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> ((Number) v).longValue()).toArray(Long[]::new);
    }

    private static CodeNodeView codeNode(ResultSet rs) throws SQLException {
        return new CodeNodeView(rs.getLong("id"), rs.getString("kind"), rs.getString("qualified_name"),
                rs.getString("file_path"), (Integer) rs.getObject("start_line"));
    }

    private static EntryPointView entryPoint(ResultSet rs) throws SQLException {
        return new EntryPointView(rs.getLong("id"), rs.getString("http_method"), rs.getString("path"),
                rs.getString("handler"), rs.getString("file_path"), (Integer) rs.getObject("start_line"));
    }

    private static SinkView sink(ResultSet rs) throws SQLException {
        return new SinkView(rs.getLong("id"), rs.getString("category"), rs.getString("cwe"), rs.getString("api"),
                rs.getString("method"), rs.getString("snippet"), rs.getString("file_path"),
                (Integer) rs.getObject("start_line"));
    }
}
