package com.codeagent.codegraph.ingest;

import com.codeagent.codegraph.domain.CodeEdgeKind;
import com.codeagent.codegraph.domain.CodeNodeKind;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * DB에 저장하기 전의 코드 그래프. 노드는 (종류, 정규화된 이름)으로 식별한다.
 */
public class CodeGraphDraft {

    public record NodeDraft(CodeNodeKind kind, String qualifiedName, String filePath,
                            Integer startLine, Integer endLine, Map<String, Object> props) {

        public String key() {
            return CodeGraphDraft.key(kind, qualifiedName);
        }
    }

    public record EdgeDraft(String srcKey, String dstKey, CodeEdgeKind kind) {
    }

    private final Map<String, NodeDraft> nodes = new LinkedHashMap<>();
    private final Set<EdgeDraft> edges = new LinkedHashSet<>();
    private int unresolvedCalls;

    public static String key(CodeNodeKind kind, String qualifiedName) {
        return kind.name() + "|" + qualifiedName;
    }

    public String addNode(NodeDraft node) {
        nodes.putIfAbsent(node.key(), node);
        return node.key();
    }

    public void addEdge(String srcKey, String dstKey, CodeEdgeKind kind) {
        edges.add(new EdgeDraft(srcKey, dstKey, kind));
    }

    public void countUnresolvedCall() {
        unresolvedCalls++;
    }

    public Collection<NodeDraft> nodes() {
        return nodes.values();
    }

    public NodeDraft node(String key) {
        return nodes.get(key);
    }

    public Set<EdgeDraft> edges() {
        return edges;
    }

    public long count(CodeNodeKind kind) {
        return nodes.values().stream().filter(n -> n.kind() == kind).count();
    }

    public long count(CodeEdgeKind kind) {
        return edges.stream().filter(e -> e.kind() == kind).count();
    }

    public int unresolvedCalls() {
        return unresolvedCalls;
    }
}
