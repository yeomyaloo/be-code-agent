package com.codeagent.agent.tool;

import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListEntryPointsToolTest {

    @Test
    void Flask_Django_진입점은_인증_단서를_보여준다() {
        assertThat(ListEntryPointsTool.dependencies(entry(null, "django", List.of("permission_classes=[IsAuthenticated]"))))
                .isEqualTo(" · 인증 단서: permission_classes=[IsAuthenticated]");
        assertThat(ListEntryPointsTool.dependencies(entry(null, "flask", List.of())))
                .isEqualTo(" · 인증 단서 없음(전역 설정은 따로 확인)");
    }

    @Test
    void FastAPI_는_Depends_를_Java_는_아무것도_보여주지_않는다() {
        assertThat(ListEntryPointsTool.dependencies(entry(List.of(), "fastapi", null))).isEqualTo(" · Depends 없음");
        assertThat(ListEntryPointsTool.dependencies(entry(null, null, null))).isEmpty();
    }

    private static EntryPointView entry(List<String> dependencies, String framework, List<String> guards) {
        return new EntryPointView(1L, "GET", "/x", "h", "a.py", 1, dependencies, framework, guards);
    }
}
