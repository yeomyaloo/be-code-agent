package com.codeagent.agent.tool;

import com.codeagent.analysisgraph.domain.WorkerTrace;
import com.codeagent.analysisgraph.domain.WorkerTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SearchWorkerTracesTool implements AgentTool {

    private static final int LIMIT = 10;
    private static final int CONTENT_LENGTH = 400;

    private final WorkerTraceRepository workerTraceRepository;

    @Override
    public String name() {
        return "search_worker_traces";
    }

    @Override
    public String description() {
        return "같은 분석 작업에서 다른 실행 담당이 남긴 분석 기록(도구 호출·결과·판단)을 검색한다. "
                + "같은 코드를 이미 누가 봤는지 확인해서 중복 분석을 줄일 때 쓴다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of("query", Map.of("type", "string", "description", "검색어 (예: UserServiceImpl findByName)"));
    }

    @Override
    public List<String> required() {
        return List.of("query");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        List<WorkerTrace> traces = workerTraceRepository.search(context.jobId(), input.string("query"), LIMIT);
        if (traces.isEmpty()) {
            return "검색 결과 없음";
        }
        return traces.stream()
                .map(t -> "[" + t.getWorkerId() + " / 할 일 #" + t.getIntentionId() + " / " + t.getRole()
                        + (t.getToolName() == null ? "" : " " + t.getToolName()) + "] " + abbreviate(t.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private static String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= CONTENT_LENGTH ? content : content.substring(0, CONTENT_LENGTH) + "...";
    }
}
