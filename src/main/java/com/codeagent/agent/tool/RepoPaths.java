package com.codeagent.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/**
 * 저장소 밖 파일에 접근하지 못하게 경로를 검사한다.
 */
final class RepoPaths {

    static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", ".idea", "build", "target", "out", "node_modules");

    private RepoPaths() {
    }

    static Path resolve(Path repoRoot, String relativePath) {
        Path root = repoRoot.toAbsolutePath().normalize();
        try {
            Path resolved = root.resolve(relativePath.replace('\\', '/')).normalize();
            if (!resolved.startsWith(root)) {
                throw new ToolInputException("저장소 밖 경로는 읽을 수 없음: " + relativePath);
            }
            // 심볼릭 링크로 저장소 밖을 가리키는 경우도 막는다
            if (Files.exists(resolved) && !resolved.toRealPath().startsWith(root.toRealPath())) {
                throw new ToolInputException("저장소 밖 경로는 읽을 수 없음: " + relativePath);
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new ToolInputException("잘못된 경로: " + relativePath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String relative(Path repoRoot, Path file) {
        return repoRoot.toAbsolutePath().normalize().relativize(file).toString().replace('\\', '/');
    }

    static boolean isSkipped(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
