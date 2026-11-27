package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeSupportTest {

    @Test
    void Node_저장소는_grep_기본_대상이_JS_TS_다() {
        assertThat(GrepCodeTool.defaultGlob(List.of("node"))).isEqualTo("**.{js,ts,mjs,cjs}");
        assertThat(GrepCodeTool.defaultGlob(List.of("java", "node"))).isEqualTo("**.{java,js,ts,mjs,cjs}");
        assertThat(GrepCodeTool.defaultGlob(List.of())).isEqualTo("**.java");
    }

    @Test
    void Node_이름은_파일_이름부터_줄인다() {
        assertThat(WorkerPrompts.shortName("src/routes/products.js#GET /search")).isEqualTo("products.js#GET /search");
        assertThat(WorkerPrompts.shortName("com.example.UserServiceImpl#search(String)")).isEqualTo("UserServiceImpl#search(String)");
        assertThat(WorkerPrompts.shortName("app.routers.scan.run_nmap")).isEqualTo("scan.run_nmap");
    }
}
