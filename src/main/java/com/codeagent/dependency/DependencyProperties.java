package com.codeagent.dependency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 의존성 취약점 검사 설정.
 *
 * @param osvEnabled      false 면 OSV 에 묻지 않고 빌드 파일의 라이브러리 목록만 보여준다 (외부로 나가는 요청을 막아야 할 때)
 * @param osvUrl          OSV API 주소
 * @param timeoutSeconds  OSV 요청 하나의 제한 시간
 * @param maxDependencies 한 번에 묻는 라이브러리 수 상한
 */
@ConfigurationProperties("codeagent.dependencies")
public record DependencyProperties(boolean osvEnabled, String osvUrl, int timeoutSeconds, int maxDependencies) {

    public DependencyProperties {
        osvUrl = osvUrl == null || osvUrl.isBlank() ? "https://api.osv.dev" : osvUrl.replaceAll("/+$", "");
        timeoutSeconds = timeoutSeconds <= 0 ? 15 : timeoutSeconds;
        maxDependencies = maxDependencies <= 0 ? 500 : maxDependencies;
    }
}
