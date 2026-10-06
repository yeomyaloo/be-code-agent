package com.codeagent.agent.tool;

import java.nio.file.Path;

/**
 * 도구가 실행되는 맥락. 어떤 저장소의 어떤 분석 작업·할 일인지 담는다.
 */
public record ToolContext(Long projectId, Path repoRoot, Long jobId, Long intentionId, String workerId) {
}
