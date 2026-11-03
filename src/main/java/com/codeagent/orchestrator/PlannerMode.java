package com.codeagent.orchestrator;

import java.util.Locale;

/**
 * 할 일을 누가 만드는지.
 */
public enum PlannerMode {
    /** LLM 계획 담당이 코드 그래프와 조사 결과를 보고 여러 회차에 걸쳐 할 일을 정한다 */
    LLM,
    /** 진입점 → 위험 지점 경로마다 할 일을 하나씩 기계적으로 만든다 */
    PATHS;

    public static PlannerMode parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("planner는 llm 또는 paths여야 함: " + value);
        }
    }
}
