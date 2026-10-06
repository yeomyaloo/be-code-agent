package com.codeagent.codegraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    @Enumerated(EnumType.STRING)
    private CodeNodeKind kind;

    private String qualifiedName;

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    @JdbcTypeCode(SqlTypes.JSON)
    private String props;

    @Builder
    private CodeNode(Long projectId, CodeNodeKind kind, String qualifiedName,
                     String filePath, Integer startLine, Integer endLine, String props) {
        this.projectId = projectId;
        this.kind = kind;
        this.qualifiedName = qualifiedName;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.props = props == null ? "{}" : props;
    }
}
