package com.codeagent.analysisgraph.domain;

public enum AnalysisNodeStatus {
    OPEN, CLAIMED, DONE, FAILED,
    /** 사람이 승인 단계에서 뺀 할 일 */
    SKIPPED,
    CONFIRMED, REJECTED, UNCERTAIN
}
