package com.codeagent.codegraph.domain;

public enum CodeEdgeKind {
    CONTAINS, CALLS, ROUTES_TO,
    /** 상위 타입(인터페이스·부모 클래스)의 메서드 → 그 메서드를 구현·재정의한 메서드 */
    OVERRIDDEN_BY
}
