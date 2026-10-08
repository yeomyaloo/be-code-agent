package com.codeagent.analysisgraph.domain;

/**
 * 사람이 발견을 검토한 결과. 검증 담당의 판정(CONFIRMED / REJECTED / UNCERTAIN)과 별개로 남는다.
 */
public enum ReviewDecision {
    /** 실제 취약점으로 인정, 조치 필요 */
    ACCEPTED,
    /** 오탐 */
    FALSE_POSITIVE,
    /** 조치 완료 */
    FIXED,
    /** 위험을 알고 받아들임 (조치하지 않음) */
    WONT_FIX
}
