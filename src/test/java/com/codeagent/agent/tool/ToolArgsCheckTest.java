package com.codeagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 에이전트가 자주 틀리는 인자를 한 번에 알려 주는지 */
class ToolArgsCheckTest {

    /** 발견 판정처럼 생긴 도구 (실제 도구는 저장소·DB 가 있어야 만들 수 있다) */
    private static final AgentTool VERDICT = new AgentTool() {
        @Override
        public String name() {
            return "submit_verdict";
        }

        @Override
        public String description() {
            return "발견 판정";
        }

        @Override
        public Map<String, Map<String, Object>> properties() {
            return Map.of(
                    "finding_id", Map.of("type", "integer", "description", "발견 노드 id"),
                    "verdict", Map.of("type", "string", "enum", List.of("CONFIRMED", "REJECTED"), "description", "판정"),
                    "reasoning", Map.of("type", "string", "description", "판정 근거"),
                    "evidence", Map.of("type", "array", "description", "근거 위치",
                            "items", Map.of("type", "object", "properties", Map.of(
                                    "file", Map.of("type", "string"), "line", Map.of("type", "integer")),
                                    "required", List.of("file"))));
        }

        @Override
        public List<String> required() {
            return List.of("finding_id", "verdict", "reasoning");
        }

        @Override
        public String execute(ToolInput input, ToolContext context) {
            return "ok";
        }
    };

    @Test
    void 비슷한_이름의_인자와_빠진_필수_인자를_한_번에_알려_준다() {
        assertThatThrownBy(() -> ToolArgsCheck.validate(VERDICT, Map.of("finding", 3, "verdict", "maybe",
                "evidence", List.of(Map.of("path", "A.java", "line", "열")))))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("finding: 이 도구의 인자가 아님. finding_id 을(를) 말한 건가요?")
                .hasMessageContaining("reasoning: 꼭 넣어야 함 (판정 근거)")
                .hasMessageContaining("verdict: [CONFIRMED, REJECTED] 중 하나여야 함 (받은 값: maybe)")
                .hasMessageContaining("evidence[0].file: 꼭 넣어야 함")
                .hasMessageContaining("evidence[0].line: 정수여야 함")
                .hasMessageContaining("reasoning (필수)");
    }

    @Test
    void reason_은_reasoning_으로_받는다() {
        Map<String, Object> input = ToolArgsCheck.normalize(VERDICT, Map.of("finding_id", 3, "verdict", "CONFIRMED", "reason", "근거"));
        assertThat(input).isEqualTo(Map.of("finding_id", 3, "verdict", "CONFIRMED", "reasoning", "근거"));
        assertThatCode(() -> ToolArgsCheck.validate(VERDICT, input)).doesNotThrowAnyException();
    }

    @Test
    void 맞는_인자는_그대로_통과한다() {
        assertThatCode(() -> ToolArgsCheck.validate(VERDICT, Map.of("finding_id", 3, "verdict", "confirmed", "reasoning", "근거")))
                .doesNotThrowAnyException();
        assertThat(ToolArgsCheck.closest("filePattern", List.of("pattern", "file_glob"))).isEqualTo("pattern");
    }
}
