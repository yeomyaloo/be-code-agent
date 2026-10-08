package com.codeagent.project.source;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 저장소를 얕게(최신 커밋 하나만) 클론한다.
 * 받은 코드는 읽기만 하므로 서브모듈은 받지 않고, 저장소 밖을 가리킬 수 있는 심볼릭 링크는 지운다.
 */
@Slf4j
public class GitCloner {

    public record CloneResult(String branch, String commitSha) {
    }

    private final int timeoutSeconds;
    private final long maxBytes;

    public GitCloner(int timeoutSeconds, long maxBytes) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxBytes = maxBytes;
    }

    /**
     * @param url    검사를 마친 주소 ({@link GitUrlPolicy#validate})
     * @param branch 받을 브랜치. null이면 원격 저장소의 기본 브랜치
     * @param target 비어 있는(또는 없는) 디렉터리
     */
    public CloneResult cloneInto(String url, String branch, Path target) {
        try {
            String branchRef = Constants.R_HEADS + (branch != null ? branch : defaultBranch(url));
            CloneCommand command = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(target.toFile())
                    .setDepth(1)
                    .setBranch(branchRef)
                    .setBranchesToClone(List.of(branchRef))
                    .setCloneSubmodules(false)
                    .setTimeout(timeoutSeconds);
            CloneResult result;
            try (Git git = command.call()) {
                var head = git.getRepository().resolve(Constants.HEAD);
                if (head == null) {
                    throw new IllegalArgumentException("브랜치를 찾을 수 없음: " + branchRef.substring(Constants.R_HEADS.length()));
                }
                result = new CloneResult(git.getRepository().getBranch(), head.name());
            }
            int removedLinks = removeSymbolicLinks(target);
            if (removedLinks > 0) {
                log.info("심볼릭 링크 {}개를 지움: {}", removedLinks, url);
            }
            long size = sizeOf(target);
            if (size > maxBytes) {
                throw new IllegalArgumentException("저장소가 너무 큼: " + size / 1024 / 1024 + "MB (상한 " + maxBytes / 1024 / 1024 + "MB)");
            }
            return result;
        } catch (GitAPIException e) {
            deleteQuietly(target);
            throw new IllegalArgumentException("클론 실패: " + e.getMessage(), e);
        } catch (IOException e) {
            deleteQuietly(target);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    /** 원격 저장소의 HEAD가 가리키는 브랜치 이름 */
    private String defaultBranch(String url) throws GitAPIException {
        Map<String, Ref> refs = Git.lsRemoteRepository().setRemote(url).setTimeout(timeoutSeconds).callAsMap();
        Ref head = refs.get(Constants.HEAD);
        if (head == null) {
            throw new IllegalArgumentException("빈 저장소이거나 기본 브랜치를 알 수 없음: " + url);
        }
        if (head.isSymbolic()) {
            return Repository.shortenRefName(head.getTarget().getName());
        }
        // 서버가 HEAD를 심볼릭 참조로 알려주지 않으면 같은 커밋을 가리키는 브랜치를 고른다
        Collection<Ref> branches = refs.values().stream()
                .filter(r -> r.getName().startsWith(Constants.R_HEADS) && head.getObjectId().equals(r.getObjectId()))
                .toList();
        return branches.stream()
                .map(r -> r.getName().substring(Constants.R_HEADS.length()))
                .filter(n -> n.equals("main") || n.equals("master"))
                .findFirst()
                .or(() -> branches.stream().map(r -> r.getName().substring(Constants.R_HEADS.length())).findFirst())
                .orElseThrow(() -> new IllegalArgumentException("기본 브랜치를 알 수 없음. branch를 지정할 것: " + url));
    }

    static int removeSymbolicLinks(Path root) {
        List<Path> links = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isSymbolicLink).forEach(links::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path link : links) {
            try {
                Files.delete(link);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return links.size();
    }

    private static long sizeOf(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    // .git/objects 의 파일은 읽기 전용이라 Windows에서 지우려면 쓰기 권한을 먼저 준다
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("삭제 실패: {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("삭제 실패: {}", root);
        }
    }
}
