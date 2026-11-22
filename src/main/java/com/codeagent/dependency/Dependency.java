package com.codeagent.dependency;

/**
 * 빌드 파일에 적힌 라이브러리 하나 (Maven 좌표).
 *
 * @param file  저장소 기준 상대 경로
 * @param line  빌드 파일의 줄 번호 (모르면 0)
 * @param scope 어디서 나왔는지 (dependency, parent, plugin, lockfile, test 등)
 */
public record Dependency(String group, String artifact, String version, String file, int line, String scope) {

    /** OSV 의 Maven 패키지 이름 형식 (group:artifact) */
    public String coordinate() {
        return group + ":" + artifact;
    }
}
