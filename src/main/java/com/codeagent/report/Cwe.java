package com.codeagent.report;

import java.util.Map;

/**
 * 보고서에 쓰는 CWE 이름. 목록에 없으면 번호만 쓴다.
 */
final class Cwe {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("CWE-22", "경로 조작 (Path Traversal)"),
            Map.entry("CWE-74", "인젝션 (JNDI 등)"),
            Map.entry("CWE-78", "OS 명령어 인젝션"),
            Map.entry("CWE-79", "크로스 사이트 스크립팅 (XSS)"),
            Map.entry("CWE-89", "SQL 인젝션"),
            Map.entry("CWE-94", "코드 인젝션"),
            Map.entry("CWE-287", "부적절한 인증"),
            Map.entry("CWE-502", "신뢰할 수 없는 데이터 역직렬화"),
            Map.entry("CWE-601", "오픈 리다이렉트"),
            Map.entry("CWE-611", "XML 외부 엔티티 (XXE)"),
            Map.entry("CWE-862", "권한 검사 누락"),
            Map.entry("CWE-917", "표현식 언어 인젝션"),
            Map.entry("CWE-918", "서버 측 요청 위조 (SSRF)"));

    private Cwe() {
    }

    static String id(String cwe) {
        return cwe == null || cwe.isBlank() ? "UNKNOWN" : cwe.strip().toUpperCase();
    }

    static String name(String cwe) {
        return NAMES.getOrDefault(id(cwe), id(cwe));
    }

    /** CWE-89 → https://cwe.mitre.org/data/definitions/89.html */
    static String url(String cwe) {
        String id = id(cwe);
        return id.matches("CWE-\\d+") ? "https://cwe.mitre.org/data/definitions/" + id.substring(4) + ".html" : null;
    }
}
