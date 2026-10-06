package com.codeagent.api;

import com.codeagent.codegraph.ingest.CodeGraphIndexer;
import com.codeagent.codegraph.ingest.CodeGraphIndexer.IndexResult;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final CodeGraphIndexer codeGraphIndexer;

    public record CreateProjectRequest(@NotBlank String name, @NotBlank String repoPath, String language) {
    }

    public record ProjectResponse(Long id, String name, String repoPath, String language, OffsetDateTime createdAt) {

        static ProjectResponse from(Project project) {
            return new ProjectResponse(project.getId(), project.getName(), project.getRepoPath(),
                    project.getLanguage(), project.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        Path repoPath = Path.of(request.repoPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoPath)) {
            throw new IllegalArgumentException("저장소 경로가 디렉터리가 아님: " + repoPath);
        }
        String language = request.language() == null ? "java" : request.language();
        return ProjectResponse.from(projectRepository.save(new Project(request.name(), repoPath.toString(), language)));
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(ProjectResponse::from).toList();
    }

    @PostMapping("/{projectId}/index")
    public IndexResult index(@PathVariable Long projectId) {
        return codeGraphIndexer.index(projectId);
    }
}
