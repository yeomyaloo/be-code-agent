package com.codeagent.dependency;

import com.codeagent.dependency.DependencyDiff.VersionChange;
import com.codeagent.dependency.DependencyService.ScanSummary;
import com.codeagent.dependency.DependencyService.ScanView;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 라이브러리 검사 기록과 지난 검사와의 비교. OSV 는 흉내 내고, 로컬 PostgreSQL 이 필요하다.
 */
@SpringBootTest
class DependencyScanFlowTest {

    private static final Vulnerability YAML_RCE = new Vulnerability("GHSA-mjmj-j48q-9wg2", List.of("CVE-2022-1471"),
            "SnakeYaml Constructor Deserialization", "high", List.of("2.0"), List.of("CWE-502"), "https://osv.dev/vulnerability/GHSA-mjmj-j48q-9wg2");
    private static final Vulnerability TEXT4SHELL = new Vulnerability("GHSA-599f-7c49-w659", List.of("CVE-2022-42889"),
            "Arbitrary code execution in Apache Commons Text", "critical", List.of("1.10.0"), List.of("CWE-94"), "https://osv.dev/vulnerability/GHSA-599f-7c49-w659");

    @Autowired
    private DependencyService dependencyService;
    @Autowired
    private ProjectRepository projectRepository;

    @MockitoBean
    private OsvClient osvClient;

    @TempDir
    Path repo;

    @Test
    void 검사를_기록하고_지난_검사와_비교한다() throws Exception {
        // snakeyaml 1.29 만 취약, commons-text 1.9 도 취약
        when(osvClient.query(any())).thenAnswer(invocation -> {
            List<Dependency> asked = invocation.getArgument(0);
            Map<Dependency, List<Vulnerability>> result = new LinkedHashMap<>();
            for (Dependency d : asked) {
                List<Vulnerability> vulns = List.of();
                if (d.artifact().equals("snakeyaml") && d.version().equals("1.29")) {
                    vulns = List.of(YAML_RCE);
                } else if (d.artifact().equals("commons-text") && d.version().equals("1.9")) {
                    vulns = List.of(TEXT4SHELL);
                }
                result.put(d, vulns);
            }
            return result;
        });
        String pom = Files.readString(Path.of("src/test/resources/fixtures/deps-maven/pom.xml"));
        Files.writeString(repo.resolve("pom.xml"), pom);
        String firstCommit = commit("첫 커밋");
        Project project = projectRepository.save(new Project("deps-history", repo.toString(), "java"));

        // 폴더 경로로 등록한 저장소도 .git 의 HEAD 를 커밋으로 쓴다
        assertThat(project.currentCommit()).isEqualTo(firstCommit);
        ScanView first = dependencyService.scan(project.getId());
        assertThat(first.commitSha()).isEqualTo(firstCommit);
        assertThat(first.diff()).isNull();

        // snakeyaml 을 2.0 으로 올리고, junit 을 빼고, commons-text 1.9 를 넣는다
        Files.writeString(repo.resolve("pom.xml"), pom
                .replace("<snakeyaml.version>1.29</snakeyaml.version>", "<snakeyaml.version>2.0</snakeyaml.version>")
                .replace("<artifactId>junit</artifactId>\n            <version>4.12</version>", "<artifactId>commons-text</artifactId>\n            <version>1.9</version>")
                .replace("<groupId>junit</groupId>", "<groupId>org.apache.commons</groupId>"));
        String secondCommit = commit("라이브러리 올림");

        ScanView second = dependencyService.scan(project.getId());

        assertThat(second.commitSha()).isEqualTo(secondCommit);
        DependencyDiff diff = second.diff();
        assertThat(diff.previousScanId()).isEqualTo(first.id());
        assertThat(diff.previousCommitSha()).isEqualTo(firstCommit);
        assertThat(diff.changed()).containsExactly(new VersionChange("org.yaml", "snakeyaml", "1.29", "2.0"));
        assertThat(diff.added()).containsExactly("org.apache.commons:commons-text:1.9");
        assertThat(diff.removed()).containsExactly("junit:junit:4.12");
        assertThat(diff.vulnerabilitiesCompared()).isTrue();
        assertThat(diff.newVulnerabilities()).extracting(DependencyDiff.VulnerabilityChange::displayId).containsExactly("CVE-2022-42889");
        assertThat(diff.resolvedVulnerabilities()).extracting(DependencyDiff.VulnerabilityChange::displayId).containsExactly("CVE-2022-1471");
        assertThat(DependencyService.summarize(second)).contains(
                "버전 변경 org.yaml:snakeyaml 1.29 → 2.0", "새 취약점 CVE-2022-42889 [critical]", "해결된 취약점 CVE-2022-1471");

        // 기록은 최근 것부터, 저장한 검사를 다시 열어도 같은 비교가 나온다
        assertThat(dependencyService.history(project.getId()))
                .extracting(ScanSummary::id, ScanSummary::commitSha, ScanSummary::vulnerabilities)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(second.id(), secondCommit, 1),
                        org.assertj.core.groups.Tuple.tuple(first.id(), firstCommit, 1));
        ScanView reopened = dependencyService.get(project.getId(), second.id());
        assertThat(reopened.diff().changed()).isEqualTo(diff.changed());
        assertThat(reopened.report().vulnerable()).extracting(DependencyService.DependencyView::artifact).containsExactly("commons-text");
        assertThat(DependencyService.summarize(dependencyService.get(project.getId(), first.id()))).contains("이번이 첫 검사");

        Project other = projectRepository.save(new Project("other", repo.toString(), "java"));
        assertThatThrownBy(() -> dependencyService.get(other.getId(), second.id())).hasMessageContaining("검사 기록이 아님");
    }

    @Test
    void 한쪽이라도_OSV_에_묻지_못했으면_취약점은_비교하지_않는다() throws Exception {
        when(osvClient.query(any())).thenThrow(new OsvClient.OsvException("OSV 에 연결하지 못함"));
        Files.writeString(repo.resolve("pom.xml"), Files.readString(Path.of("src/test/resources/fixtures/deps-maven/pom.xml")));
        Project project = projectRepository.save(new Project("deps-offline", repo.toString(), "java"));

        dependencyService.scan(project.getId());
        ScanView second = dependencyService.scan(project.getId());

        assertThat(second.commitSha()).isNull();
        assertThat(second.diff().vulnerabilitiesCompared()).isFalse();
        assertThat(second.diff().hasChanges()).isFalse();
    }

    private String commit(String message) throws Exception {
        try (Git git = Files.isDirectory(repo.resolve(".git")) ? Git.open(repo.toFile()) : Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage(message).setAuthor("test", "test@example.com").setCommitter("test", "test@example.com")
                    .setSign(false).call().getName();
        }
    }
}
