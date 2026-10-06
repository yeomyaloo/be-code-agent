package com.codeagent.api;

import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/code-graph")
@RequiredArgsConstructor
public class CodeGraphController {

    private final CodeGraphQuery codeGraphQuery;

    @GetMapping("/entry-points")
    public List<EntryPointView> entryPoints(@PathVariable Long projectId) {
        return codeGraphQuery.entryPoints(projectId);
    }

    @GetMapping("/sinks")
    public List<SinkView> sinks(@PathVariable Long projectId) {
        return codeGraphQuery.sinks(projectId);
    }

    @GetMapping("/sink-paths")
    public List<SinkPathView> sinkPaths(@PathVariable Long projectId,
                                        @RequestParam(defaultValue = "12") int maxDepth) {
        return codeGraphQuery.sinkPaths(projectId, Math.min(maxDepth, 30));
    }

    @GetMapping("/nodes/{nodeId}/callers")
    public List<CodeNodeView> callers(@PathVariable Long projectId, @PathVariable Long nodeId) {
        return codeGraphQuery.callers(nodeId);
    }

    @GetMapping("/nodes/{nodeId}/callees")
    public List<CodeNodeView> callees(@PathVariable Long projectId, @PathVariable Long nodeId) {
        return codeGraphQuery.callees(nodeId);
    }
}
