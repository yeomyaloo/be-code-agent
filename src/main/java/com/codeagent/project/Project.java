package com.codeagent.project;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** 분석할 소스가 있는 디렉터리. Git 주소로 등록했으면 workspace 아래 클론한 위치 */
    private String repoPath;

    private String language;

    private String gitUrl;

    private String gitBranch;

    private String commitSha;

    private OffsetDateTime syncedAt;

    @Column(updatable = false)
    private OffsetDateTime createdAt;

    public Project(String name, String repoPath, String language) {
        this.name = name;
        this.repoPath = repoPath;
        this.language = language;
        this.createdAt = OffsetDateTime.now();
    }

    public static Project fromGit(String name, String gitUrl, String language, String repoPath, String branch, String commitSha) {
        Project project = new Project(name, repoPath, language);
        project.gitUrl = gitUrl;
        project.synced(repoPath, branch, commitSha);
        return project;
    }

    public boolean isGit() {
        return gitUrl != null;
    }

    /** 다시 클론한 결과를 반영한다 */
    public void synced(String repoPath, String branch, String commitSha) {
        this.repoPath = repoPath;
        this.gitBranch = branch;
        this.commitSha = commitSha;
        this.syncedAt = OffsetDateTime.now();
    }
}
