package com.codeagent.dependency;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 라이브러리 취약점 검사 한 번의 기록. 결과 전체(DependencyReport)를 JSON 으로 남겨 다음 검사와 비교한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DependencyScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    /** 검사한 커밋 (모르면 null) */
    private String commitSha;

    /** OSV 에 물어봤는지 */
    private boolean checked;

    private String error;

    private int dependencyCount;

    private int vulnerableCount;

    private int vulnerabilityCount;

    @JdbcTypeCode(SqlTypes.JSON)
    private String report;

    private OffsetDateTime createdAt;

    public DependencyScan(Long projectId, String commitSha, boolean checked, String error, int dependencyCount,
                          int vulnerableCount, int vulnerabilityCount, String report) {
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.checked = checked;
        this.error = error;
        this.dependencyCount = dependencyCount;
        this.vulnerableCount = vulnerableCount;
        this.vulnerabilityCount = vulnerabilityCount;
        this.report = report;
        this.createdAt = OffsetDateTime.now();
    }
}
