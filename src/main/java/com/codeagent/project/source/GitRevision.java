package com.codeagent.project.source;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 저장소 폴더의 현재 커밋(HEAD). 서버 폴더 경로로 등록한 저장소도 .git 이 있으면 커밋을 알 수 있다.
 * .git 을 읽기만 하고 아무것도 바꾸지 않는다.
 */
public final class GitRevision {

    private GitRevision() {
    }

    public static Optional<String> head(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot.resolve(".git"))) {
            return Optional.empty();
        }
        try (Repository repository = new FileRepositoryBuilder().setGitDir(repoRoot.resolve(".git").toFile())
                .setMustExist(true).build()) {
            ObjectId head = repository.resolve("HEAD");
            return Optional.ofNullable(head).map(ObjectId::getName);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
