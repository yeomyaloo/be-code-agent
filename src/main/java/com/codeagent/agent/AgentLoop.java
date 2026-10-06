package com.codeagent.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.ToolContext;
import com.codeagent.agent.tool.ToolInput;
import com.codeagent.agent.tool.ToolInputException;
import com.codeagent.analysisgraph.domain.WorkerTrace;
import com.codeagent.analysisgraph.domain.WorkerTraceRepository;
import com.codeagent.llm.LlmProperties;
import com.codeagent.llm.ModelTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 도구 호출 반복 흐름. Claude가 도구를 부르면 실행해서 결과를 돌려주고, 끝낼 때까지 반복한다.
 * 모든 단계는 worker_trace 에 남겨 다른 실행 담당이 검색할 수 있게 한다.
 */
@Slf4j
@Component
public class AgentLoop {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final int TRACE_MAX_LENGTH = 4000;

    private final AnthropicClient client;
    private final LlmProperties llmProperties;
    private final WorkerTraceRepository traceRepository;
    private final JsonMapper jsonMapper;

    public AgentLoop(@Lazy AnthropicClient client, LlmProperties llmProperties,
                     WorkerTraceRepository traceRepository, JsonMapper jsonMapper) {
        this.client = client;
        this.llmProperties = llmProperties;
        this.traceRepository = traceRepository;
        this.jsonMapper = jsonMapper;
    }

    public record AgentRequest(ModelTier tier, String systemPrompt, String userPrompt, List<AgentTool> tools,
                               ToolContext context, int maxSteps, long tokenLimit) {
    }

    public enum Outcome {
        COMPLETED, STEP_LIMIT, BUDGET_EXHAUSTED, TRUNCATED, REFUSED, CONTEXT_EXCEEDED, FAILED
    }

    public record AgentRun(Outcome outcome, String finalText, int steps, long inputTokens, long outputTokens,
                           long cacheReadTokens, String error) {
    }

    public AgentRun run(AgentRequest request) {
        LlmProperties.Tier tier = llmProperties.tier(request.tier());
        Map<String, AgentTool> tools = request.tools().stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(tier.model())
                .maxTokens(llmProperties.maxTokens())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(tier.effort())).build())
                // 시스템 프롬프트(와 그 앞의 도구 정의)는 할 일마다 같으므로 캐시한다
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(request.userPrompt());
        tools.values().forEach(t -> params.addTool(t.toSdkTool()));
        if (llmProperties.fallbacks()) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Usage usage = new Usage();
        TraceWriter trace = new TraceWriter(request.context());
        trace.write("USER", null, request.userPrompt());
        String lastText = "";

        while (true) {
            if (usage.steps >= request.maxSteps()) {
                return usage.result(Outcome.STEP_LIMIT, lastText, null);
            }
            if (usage.total() >= request.tokenLimit()) {
                return usage.result(Outcome.BUDGET_EXHAUSTED, lastText, null);
            }

            Message response;
            try {
                response = client.messages().create(params.build());
            } catch (AnthropicException e) {
                log.warn("LLM 호출 실패 (할 일 #{}): {}", request.context().intentionId(), e.getMessage());
                return usage.result(Outcome.FAILED, lastText, e.getMessage());
            }
            usage.add(response);
            // 응답 전체(생각 블록 포함)를 그대로 이어 붙인다. 기록을 고치면 다음 요청이 거절될 수 있다
            params.addMessage(response);

            String text = response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"))
                    .strip();
            if (!text.isEmpty()) {
                lastText = text;
                trace.write("ASSISTANT", null, text);
            }

            StopReason stopReason = response.stopReason().orElse(StopReason.END_TURN);
            if (StopReason.TOOL_USE.equals(stopReason)) {
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(toolUse -> results.add(executeTool(toolUse, tools, request.context(), trace)));
                }
                // 여러 도구 결과는 한 메시지에 모아서 돌려준다
                params.addMessage(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            } else if (StopReason.PAUSE_TURN.equals(stopReason)) {
                // 서버가 멈춘 지점에서 이어서 하도록 그대로 다시 보낸다
            } else if (StopReason.END_TURN.equals(stopReason) || StopReason.STOP_SEQUENCE.equals(stopReason)) {
                return usage.result(Outcome.COMPLETED, lastText, null);
            } else if (StopReason.MAX_TOKENS.equals(stopReason)) {
                // 잘린 도구 입력은 실행하지 않는다
                return usage.result(Outcome.TRUNCATED, lastText, "응답이 max_tokens에서 잘림");
            } else if (StopReason.REFUSAL.equals(stopReason)) {
                String detail = response.stopDetails().map(Object::toString).orElse("");
                trace.write("REFUSAL", null, detail);
                return usage.result(Outcome.REFUSED, lastText, "모델이 요청을 거절함 " + detail);
            } else if (StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stopReason)) {
                return usage.result(Outcome.CONTEXT_EXCEEDED, lastText, "컨텍스트 창 초과");
            } else {
                return usage.result(Outcome.FAILED, lastText, "알 수 없는 stop_reason: " + stopReason);
            }
        }
    }

    private ContentBlockParam executeTool(ToolUseBlock toolUse, Map<String, AgentTool> tools,
                                          ToolContext context, TraceWriter trace) {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = toolUse._input().convert(Map.class);
        trace.write("TOOL_CALL", toolUse.name(), jsonMapper.writeValueAsString(input == null ? Map.of() : input));

        String result;
        boolean isError = false;
        AgentTool tool = tools.get(toolUse.name());
        try {
            if (tool == null) {
                throw new ToolInputException("없는 도구: " + toolUse.name());
            }
            if (input == null) {
                throw new ToolInputException("도구 입력이 JSON 객체가 아님");
            }
            result = tool.execute(new ToolInput(input), context);
        } catch (ToolInputException e) {
            result = "입력 오류: " + e.getMessage();
            isError = true;
        } catch (RuntimeException e) {
            log.warn("도구 실행 실패: {} {}", toolUse.name(), input, e);
            result = "도구 실행 실패: " + e.getMessage();
            isError = true;
        }
        trace.write(isError ? "TOOL_ERROR" : "TOOL_RESULT", toolUse.name(), result);

        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(result)
                .isError(isError)
                .build());
    }

    private static final class Usage {
        int steps;
        long inputTokens;
        long outputTokens;
        long cacheReadTokens;

        void add(Message response) {
            steps++;
            var u = response.usage();
            long cacheRead = u.cacheReadInputTokens().orElse(0L);
            long cacheWrite = u.cacheCreationInputTokens().orElse(0L);
            inputTokens += u.inputTokens() + cacheRead + cacheWrite;
            outputTokens += u.outputTokens();
            cacheReadTokens += cacheRead;
        }

        long total() {
            return inputTokens + outputTokens;
        }

        AgentRun result(Outcome outcome, String finalText, String error) {
            return new AgentRun(outcome, finalText, steps, inputTokens, outputTokens, cacheReadTokens, error);
        }
    }

    private final class TraceWriter {
        private final ToolContext context;
        private int step;

        TraceWriter(ToolContext context) {
            this.context = context;
        }

        void write(String role, String toolName, String content) {
            String trimmed = content != null && content.length() > TRACE_MAX_LENGTH
                    ? content.substring(0, TRACE_MAX_LENGTH) + "..." : content;
            traceRepository.save(WorkerTrace.builder()
                    .jobId(context.jobId())
                    .intentionId(context.intentionId())
                    .workerId(context.workerId())
                    .step(step++)
                    .role(role)
                    .toolName(toolName)
                    .content(trimmed)
                    .build());
        }
    }
}
