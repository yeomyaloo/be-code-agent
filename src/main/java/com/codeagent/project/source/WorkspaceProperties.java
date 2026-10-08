package com.codeagent.project.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * @param dir            Git 주소로 등록한 저장소를 클론하는 디렉터리
 * @param allowedHosts   클론을 허용하는 Git 호스트 (https만)
 * @param maxSizeMb      클론한 저장소 크기 상한
 * @param timeoutSeconds 네트워크 연결·읽기 제한 시간
 * @param allowLocalPath 서버 디스크의 폴더 경로로 등록하는 것을 허용할지 (여러 사람이 쓰는 서버면 false)
 */
@ConfigurationProperties("codeagent.workspace")
public record WorkspaceProperties(Path dir, List<String> allowedHosts, long maxSizeMb, int timeoutSeconds,
                                  boolean allowLocalPath) {
}
