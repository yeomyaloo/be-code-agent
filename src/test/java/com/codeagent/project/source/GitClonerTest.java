package com.codeagent.project.source;

import com.codeagent.project.source.GitCloner.CloneResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 네트워크 없이 로컬 원격 저장소를 만들어서 클론을 확인한다.
 */
class GitClonerTest {

    @TempDir
    Path temp;

    private Path remote;
    private String remoteUrl;
    private RevCommit secondCommit;

    @BeforeEach
    void setUp() throws Exception {
        remote = temp.resolve("remote");
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("App.java"), "class App {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("첫 커밋").setSign(false).call();
            Files.writeString(remote.resolve("App.java"), "class App { void run() {} }");
            git.add().addFilepattern(".").call();
            secondCommit = git.commit().setMessage("두 번째 커밋").setSign(false).call();

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            Files.writeString(remote.resolve("Feature.java"), "class Feature {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("기능 브랜치").setSign(false).call();
            git.checkout().setName("main").call();
        }
        remoteUrl = remote.toUri().toString();
    }

    @Test
    void 기본_브랜치의_최신_커밋_하나만_받는다() throws Exception {
        Path target = temp.resolve("clone");
        CloneResult result = new GitCloner(30, Long.MAX_VALUE).cloneInto(remoteUrl, null, target);

        assertThat(result.branch()).isEqualTo("main");
        assertThat(result.commitSha()).isEqualTo(secondCommit.name());
        assertThat(Files.readString(target.resolve("App.java"))).contains("run()");
        assertThat(target.resolve("Feature.java")).doesNotExist();
        try (Git git = Git.open(target.toFile())) {
            assertThat(git.log().call()).as("얕은 클론").hasSize(1);
        }
    }

    @Test
    void 지정한_브랜치를_받는다() {
        Path target = temp.resolve("clone");
        CloneResult result = new GitCloner(30, Long.MAX_VALUE).cloneInto(remoteUrl, "feature", target);

        assertThat(result.branch()).isEqualTo("feature");
        assertThat(target.resolve("Feature.java")).exists();
    }

    @Test
    void 없는_브랜치면_실패하고_폴더를_지운다() {
        Path target = temp.resolve("clone");

        assertThatThrownBy(() -> new GitCloner(30, Long.MAX_VALUE).cloneInto(remoteUrl, "nope", target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void 크기_상한을_넘으면_실패하고_폴더를_지운다() {
        Path target = temp.resolve("clone");

        assertThatThrownBy(() -> new GitCloner(30, 10).cloneInto(remoteUrl, null, target))
                .hasMessageContaining("너무 큼");
        assertThat(target).doesNotExist();
    }

    @Test
    void 심볼릭_링크를_지운다() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("repo"));
        Path link = dir.resolve("secret");
        try {
            Files.createSymbolicLink(link, temp.resolve("remote/App.java"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "이 환경은 심볼릭 링크를 만들 수 없음");
        }

        assertThat(GitCloner.removeSymbolicLinks(dir)).isEqualTo(1);
        assertThat(link).doesNotExist();
    }
}
