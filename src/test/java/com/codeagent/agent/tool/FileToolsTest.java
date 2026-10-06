package com.codeagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/vulnerable-app");
    private static final ToolContext CONTEXT = new ToolContext(1L, FIXTURE, 1L, 1L, "test");
    private static final String IMPL = "src/main/java/com/example/vuln/service/UserServiceImpl.java";

    private final ReadFileTool readFile = new ReadFileTool();
    private final GrepCodeTool grepCode = new GrepCodeTool();

    @Test
    void 줄_번호와_함께_지정한_범위를_읽는다() {
        String result = readFile.execute(input(Map.of("path", IMPL, "start_line", 23, "end_line", 26)), CONTEXT);

        assertThat(result).startsWith(IMPL + " (23-26 / 전체 27줄)")
                .contains("   25 |         return jdbcTemplate.queryForList(sql);")
                .doesNotContain("   22 |");
    }

    @Test
    void 저장소_밖_경로는_거부한다() {
        assertThatThrownBy(() -> readFile.execute(input(Map.of("path", "../../../../build.gradle")), CONTEXT))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("저장소 밖");
    }

    @Test
    void 없는_파일은_입력_오류다() {
        assertThatThrownBy(() -> readFile.execute(input(Map.of("path", "nope.java")), CONTEXT))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void 정규식과_일치하는_줄을_경로와_줄_번호로_돌려준다() {
        String result = grepCode.execute(input(Map.of("pattern", "queryForList|Runtime\\.getRuntime")), CONTEXT);

        assertThat(result.lines()).containsExactlyInAnyOrder(
                IMPL + ":25: return jdbcTemplate.queryForList(sql);",
                "src/main/java/com/example/vuln/service/CommandService.java:11: Process process = Runtime.getRuntime().exec(\"ping -c 1 \" + host);");
    }

    @Test
    void 잘못된_정규식은_입력_오류다() {
        assertThatThrownBy(() -> grepCode.execute(input(Map.of("pattern", "(unclosed")), CONTEXT))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("정규식 오류");
    }

    @Test
    void 필수_입력이_없으면_입력_오류다() {
        assertThatThrownBy(() -> grepCode.execute(input(Map.of()), CONTEXT))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("pattern");
    }

    private static ToolInput input(Map<String, Object> values) {
        return new ToolInput(values);
    }
}
