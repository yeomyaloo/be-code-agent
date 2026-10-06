package com.codeagent.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class ReadFileTool implements AgentTool {

    static final int DEFAULT_LINES = 200;
    static final int MAX_LINES = 400;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "분석 대상 저장소의 파일을 줄 번호와 함께 읽는다. 경로는 저장소 루트 기준 상대 경로. "
                + "한 번에 최대 " + MAX_LINES + "줄까지 읽으며, 긴 파일은 start_line/end_line으로 나눠 읽는다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of(
                "path", Map.of("type", "string", "description", "저장소 루트 기준 상대 경로 (예: src/main/java/com/x/UserService.java)"),
                "start_line", Map.of("type", "integer", "description", "시작 줄 (1부터, 기본 1)"),
                "end_line", Map.of("type", "integer", "description", "끝 줄 (포함, 기본 시작 줄 + " + (DEFAULT_LINES - 1) + ")"));
    }

    @Override
    public List<String> required() {
        return List.of("path");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        Path file = RepoPaths.resolve(context.repoRoot(), input.string("path"));
        if (!Files.isRegularFile(file)) {
            throw new ToolInputException("파일이 없음: " + input.string("path"));
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = (int) Math.max(1, input.optionalLong("start_line").orElse(1L));
        int end = (int) Math.min(lines.size(), input.optionalLong("end_line").orElse((long) start + DEFAULT_LINES - 1));
        end = Math.min(end, start + MAX_LINES - 1);
        if (start > lines.size()) {
            return "파일은 " + lines.size() + "줄뿐임";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(RepoPaths.relative(context.repoRoot(), file))
                .append(" (").append(start).append('-').append(end).append(" / 전체 ").append(lines.size()).append("줄)\n");
        for (int i = start; i <= end; i++) {
            sb.append(String.format("%5d | %s%n", i, lines.get(i - 1)));
        }
        return sb.toString();
    }
}
