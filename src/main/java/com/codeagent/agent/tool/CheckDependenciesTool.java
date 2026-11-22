package com.codeagent.agent.tool;

import com.codeagent.dependency.DependencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 계획 담당 전용. 빌드 파일의 라이브러리 중 알려진 취약점(CVE)이 있는 것을 보여준다 (OWASP A03 공급망).
 */
@Component
@RequiredArgsConstructor
public class CheckDependenciesTool implements AgentTool {

    public static final String NAME = "check_dependencies";

    private final DependencyService dependencyService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "pom.xml·build.gradle·gradle/libs.versions.toml·gradle.lockfile 에서 라이브러리와 버전을 읽고, "
                + "공개 취약점 DB(OSV)에 알려진 취약점(CVE·GHSA, 심각도, 고친 버전)이 있는 라이브러리를 보여준다. "
                + "지난 검사와 비교해 바뀐 버전·새 취약점·해결된 취약점도 알려준다. "
                + "취약한 라이브러리가 있으면, 그 취약점이 터지는 기능을 이 코드가 실제로 쓰는지 확인하는 할 일을 만들 때 쓴다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of();
    }

    @Override
    public List<String> required() {
        return List.of();
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        // 검사 기록으로 남겨서, 지난 검사와 달라진 점(버전 변경, 새 취약점)도 보여준다
        return DependencyService.summarize(dependencyService.scan(context.projectId()));
    }
}
