package com.codeagent.analysisgraph.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 분석 그래프 노드와 코드 그래프 노드를 잇는 연결점.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Anchor {

    @EmbeddedId
    private AnchorId id;

    public Anchor(Long analysisNodeId, Long codeNodeId, AnchorRole role) {
        this.id = new AnchorId(analysisNodeId, codeNodeId, role);
    }

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class AnchorId implements Serializable {

        private Long analysisNodeId;

        private Long codeNodeId;

        @Enumerated(EnumType.STRING)
        private AnchorRole role;

        AnchorId(Long analysisNodeId, Long codeNodeId, AnchorRole role) {
            this.analysisNodeId = analysisNodeId;
            this.codeNodeId = codeNodeId;
            this.role = role;
        }
    }
}
