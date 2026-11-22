package com.codeagent.dependency;

/**
 * 빌드 파일에 적힌 라이브러리 하나. Maven 은 group:artifact, npm 은 group 이 "npm" 이고 artifact 가 패키지 이름이다.
 *
 * @param file  저장소 기준 상대 경로
 * @param line  빌드 파일의 줄 번호 (모르면 0)
 * @param scope 어디서 나왔는지 (dependency, parent, plugin, lockfile, test 등)
 */
public record Dependency(String group, String artifact, String version, String file, int line, String scope) {

    public static final String NPM = "npm";

    /** 라이브러리를 가리키는 이름 (group:artifact, npm 이면 npm:패키지) */
    public String coordinate() {
        return group + ":" + artifact;
    }

    /** OSV 생태계 이름 */
    public String ecosystem() {
        return NPM.equals(group) ? "npm" : "Maven";
    }

    /** OSV 의 패키지 이름 (Maven 은 group:artifact, npm 은 패키지 이름) */
    public String osvName() {
        return NPM.equals(group) ? artifact : group + ":" + artifact;
    }
}
