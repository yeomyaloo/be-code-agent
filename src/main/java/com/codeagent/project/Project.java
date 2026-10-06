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

    private String repoPath;

    private String language;

    @Column(updatable = false)
    private OffsetDateTime createdAt;

    public Project(String name, String repoPath, String language) {
        this.name = name;
        this.repoPath = repoPath;
        this.language = language;
        this.createdAt = OffsetDateTime.now();
    }
}
