package com.codeagent.agent.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * 실행 담당이 쓰는 도구. 입력 스키마는 JSON Schema의 properties 형태로 정의한다.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema properties (속성 이름 → 스키마) */
    Map<String, Map<String, Object>> properties();

    List<String> required();

    /**
     * @return Claude에게 돌려줄 결과 텍스트
     * @throws ToolInputException 입력이 잘못됐을 때 (Claude에게 오류로 전달된다)
     */
    String execute(ToolInput input, ToolContext context);

    default Tool toSdkTool() {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties().forEach((name, schema) -> props.putAdditionalProperty(name, JsonValue.from(schema)));
        return Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required())
                        .build())
                .build();
    }
}
