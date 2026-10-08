package com.codeagent.api;

import com.codeagent.codegraph.ingest.CodeGraphIndexer;
import com.codeagent.codegraph.ingest.CodeGraphIndexer.IndexResult;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import com.codeagent.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final CodeGraphIndexer codeGraphIndexer;

    /**
     * gitUrl 과 repoPath 중 하나만 준다.
     *
     * @param gitUrl   https Git 주소 (허용 호스트만). name 을 생략하면 저장소 이름을 쓴다
     * @param branch   gitUrl 의 브랜치. 생략하면 기본 브랜치
     * @param repoPath 서버 디스크의 폴더 경로 (codeagent.workspace.allow-local-path 가 true 일 때만)
     */
    public record CreateProjectRequest(String name, String gitUrl, String branch, String repoPath, String language) {
    }

    public record ProjectResponse(Long id, String name, String repoPath, String language, String gitUrl,
                                  String gitBranch, String commitSha, OffsetDateTime syncedAt,
                                  OffsetDateTime createdAt) {

        static ProjectResponse from(Project project) {
            return new ProjectResponse(project.getId(), project.getName(), project.getRepoPath(),
                    project.getLanguage(), project.getGitUrl(), project.getGitBranch(), project.getCommitSha(),
                    project.getSyncedAt(), project.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@RequestBody CreateProjectRequest request) {
        boolean hasGit = request.gitUrl() != null && !request.gitUrl().isBlank();
        boolean hasPath = request.repoPath() != null && !request.repoPath().isBlank();
        if (hasGit == hasPath) {
            throw new IllegalArgumentException("gitUrl과 repoPath 중 하나만 줄 것");
        }
        Project project = hasGit
                ? projectService.registerGit(request.name(), request.gitUrl(), request.branch(), request.language())
                : projectService.registerLocal(request.name(), request.repoPath(), request.language());
        return ProjectResponse.from(project);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(ProjectResponse::from).toList();
    }

    /** Git 저장소를 최신 커밋으로 다시 받는다. 이어서 /index 로 코드 그래프를 다시 만든다 */
    @PostMapping("/{projectId}/sync")
    public ProjectResponse sync(@PathVariable Long projectId) {
        return ProjectResponse.from(projectService.sync(projectId));
    }

    @PostMapping("/{projectId}/index")
    public IndexResult index(@PathVariable Long projectId) {
        return codeGraphIndexer.index(projectId);
    }
}
