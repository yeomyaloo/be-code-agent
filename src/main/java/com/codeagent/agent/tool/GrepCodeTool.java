package com.codeagent.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@Component
public class GrepCodeTool implements AgentTool {

    static final int MAX_MATCHES = 80;
    private static final int MAX_LINE_LENGTH = 200;

    @Override
    public String name() {
        return "grep_code";
    }

    @Override
    public String description() {
        return "저장소에서 정규식(Java 문법)과 일치하는 줄을 찾는다. 결과는 '경로:줄: 내용' 형식이며 최대 "
                + MAX_MATCHES + "개까지 돌려준다. 기본 대상은 .java 파일이고 file_glob으로 바꿀 수 있다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of(
                "pattern", Map.of("type", "string", "description", "Java 정규식 (예: createNativeQuery\\(|@RequestParam)"),
                "file_glob", Map.of("type", "string", "description", "대상 파일 glob, 저장소 루트 기준 (기본 **.java, 예: **.xml, src/main/resources/**)"));
    }

    @Override
    public List<String> required() {
        return List.of("pattern");
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(input.string("pattern"));
        } catch (PatternSyntaxException e) {
            throw new ToolInputException("정규식 오류: " + e.getDescription());
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + input.optionalString("file_glob").orElse("**.java"));
        Path root = context.repoRoot().toAbsolutePath().normalize();

        List<String> matches = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))::iterator) {
                Path relative = root.relativize(file);
                if (RepoPaths.isSkipped(relative) || !matcher.matches(relative)) {
                    continue;
                }
                List<String> lines = readLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        if (matches.size() >= MAX_MATCHES) {
                            truncated = true;
                            break;
                        }
                        String line = lines.get(i).strip();
                        if (line.length() > MAX_LINE_LENGTH) {
                            line = line.substring(0, MAX_LINE_LENGTH) + "...";
                        }
                        matches.add(relative.toString().replace('\\', '/') + ":" + (i + 1) + ": " + line);
                    }
                }
                if (truncated) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (matches.isEmpty()) {
            return "일치하는 줄 없음";
        }
        String result = String.join("\n", matches);
        return truncated ? result + "\n... (" + MAX_MATCHES + "개에서 잘림. 패턴이나 file_glob을 좁힐 것)" : result;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return List.of(); // 바이너리나 UTF-8이 아닌 파일은 건너뜀
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
