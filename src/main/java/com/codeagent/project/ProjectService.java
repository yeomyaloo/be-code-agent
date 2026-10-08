package com.codeagent.project;

import com.codeagent.project.source.GitCloner;
import com.codeagent.project.source.GitCloner.CloneResult;
import com.codeagent.project.source.GitUrlPolicy;
import com.codeagent.project.source.WorkspaceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 분석 대상 저장소 등록. Git 주소면 workspace 아래로 클론하고, 로컬 경로면 그 폴더를 그대로 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final String DEFAULT_LANGUAGE = "java";

    private final ProjectRepository projectRepository;
    private final AnalysisJobRepository jobRepository;
    private final GitUrlPolicy gitUrlPolicy;
    private final GitCloner gitCloner;
    private final WorkspaceProperties workspace;

    public Project registerGit(String name, String gitUrl, String branch, String language) {
        String url = gitUrlPolicy.validate(gitUrl);
        Path target = newWorkspaceDir();
        CloneResult clone = gitCloner.cloneInto(url, blankToNull(branch), target);
        log.info("클론 완료: {} ({} @ {})", url, clone.branch(), clone.commitSha());

        String projectName = name == null || name.isBlank() ? GitUrlPolicy.repoName(url) : name;
        return projectRepository.save(Project.fromGit(projectName, url, languageOrDefault(language),
                target.toString(), clone.branch(), clone.commitSha()));
    }

    public Project registerLocal(String name, String repoPath, String language) {
        if (!workspace.allowLocalPath()) {
            throw new IllegalArgumentException("이 서버는 로컬 경로 등록을 허용하지 않음. gitUrl로 등록할 것");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("로컬 경로로 등록할 때는 name이 필요함");
        }
        Path path = Path.of(repoPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("저장소 경로가 디렉터리가 아님: " + path);
        }
        return projectRepository.save(new Project(name, path.toString(), languageOrDefault(language)));
    }

    /**
     * Git 저장소를 최신 커밋으로 다시 받는다. 새로 클론한 뒤 이전 폴더를 지운다.
     * 코드 그래프는 다시 만들지 않으므로 이어서 /index 를 호출해야 한다.
     */
    public Project sync(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("프로젝트 없음: " + projectId));
        if (!project.isGit()) {
            throw new IllegalArgumentException("Git 주소로 등록한 프로젝트만 동기화할 수 있음");
        }
        if (jobRepository.existsByProjectIdAndStatus(projectId, JobStatus.RUNNING)) {
            throw new IllegalArgumentException("분석 작업이 진행 중이라 동기화할 수 없음");
        }
        Path oldDir = Path.of(project.getRepoPath());
        Path target = newWorkspaceDir();
        CloneResult clone = gitCloner.cloneInto(project.getGitUrl(), project.getGitBranch(), target);
        project.synced(target.toString(), clone.branch(), clone.commitSha());
        projectRepository.save(project);

        if (oldDir.toAbsolutePath().normalize().startsWith(workspaceRoot())) {
            GitCloner.deleteQuietly(oldDir);
        }
        log.info("동기화 완료: {} ({} @ {})", project.getGitUrl(), clone.branch(), clone.commitSha());
        return project;
    }

    private Path newWorkspaceDir() {
        return workspaceRoot().resolve(UUID.randomUUID().toString());
    }

    private Path workspaceRoot() {
        return workspace.dir().toAbsolutePath().normalize();
    }

    private static String languageOrDefault(String language) {
        return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
