package com.codeagent.codegraph.ingest;

import java.util.Set;

/**
 * 위험 지점 규칙. type의 methods 중 하나를 호출하면 위험 지점으로 본다.
 * 생성자 호출은 메서드 이름 {@code <init>}으로 표현한다.
 */
public record SinkRule(String type, Set<String> methods, String category, String cwe) {

    public boolean matches(String typeFqn, String method) {
        return type.equals(typeFqn) && methods.contains(method);
    }

    public String api(String method) {
        return type + "." + method;
    }
}
