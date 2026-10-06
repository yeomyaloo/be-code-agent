package com.codeagent.agent;

import com.codeagent.analysisgraph.domain.AnalysisNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 검증 담당 프롬프트. 실행 담당과 별개로, 발견을 반대 입장에서 다시 확인한다.
 */
public final class VerifierPrompts {

    public static final String SYSTEM = """
            당신은 다른 분석가가 보고한 보안 취약점이 진짜인지 검증하는 Java/Spring 보안 검토자입니다.
            보고서는 틀렸을 수 있습니다. 보고서의 설명을 그대로 믿지 말고, 코드를 직접 읽어 독립적으로 판단하세요.

            반드시 확인할 것
            - 입력 출처: 위험 지점에 닿는 값이 정말 외부 사용자가 조절할 수 있는 값인가? 상수, 설정값, 서버가 만든 값, 열거형으로 제한된 값이면 공격 불가능입니다.
            - 도달 가능성: 진입점이 실제로 외부에 노출되는가? 호출 경로의 각 단계가 실제로 이어지는가? (코드 그래프는 정적 분석이라 틀릴 수 있음)
            - 방어 장치: 입력 검증, 이스케이프, 파라미터 바인딩(PreparedStatement의 ?, JPA 파라미터), 허용 목록, 경로 정규화와 기준 경로 확인,
              권한 검사(Spring Security 설정, @PreAuthorize), Bean Validation(@Valid, @Pattern) 등이 공격을 막는가?
            - 영향: 공격이 성공하면 실제로 무엇을 할 수 있는가? 보고된 심각도가 과장되지 않았는가?

            판단 기준
            - 외부 입력이 막힘 없이 위험 지점에 닿고 악용 방법이 구체적이면 confirmed.
            - 방어 장치가 공격을 막거나 입력이 외부에서 오지 않으면 rejected.
            - 판단에 필요한 코드가 저장소에 없는 등 근거가 부족할 때만 uncertain. 귀찮아서 uncertain을 고르지 마세요.

            조사를 마치면 submit_verdict를 한 번 호출해 판정을 제출하고, 한두 문장으로 마무리합니다. 모든 설명은 한국어로 씁니다.
            """;

    private VerifierPrompts() {
    }

    public static String finding(AnalysisNode finding, Map<String, Object> props, List<AnalysisNode> facts) {
        String factText = facts.isEmpty() ? "(없음)" : facts.stream()
                .map(f -> "- " + f.getTitle() + (f.getBody() == null || f.getBody().isBlank() ? "" : "\n  " + f.getBody()))
                .collect(Collectors.joining("\n"));
        return """
                검증할 발견 #%d

                제목: %s
                CWE: %s / 보고된 심각도: %s / 보고자 확신도: %s
                진입점 노드 id: %s / 위험 지점 노드 id: %s

                설명:
                %s

                공격 시나리오:
                %s

                근거 코드 위치:
                %s

                보고자가 근거로 든 사실:
                %s
                """.formatted(
                finding.getId(), finding.getTitle(),
                props.get("cwe"), props.get("severity"), props.get("confidence"),
                props.getOrDefault("sourceNodeId", "-"), props.getOrDefault("sinkNodeId", "-"),
                finding.getBody(),
                props.getOrDefault("exploitScenario", "(없음)"),
                evidence(props.get("evidence")),
                factText);
    }

    private static String evidence(Object evidence) {
        if (!(evidence instanceof List<?> list) || list.isEmpty()) {
            return "(없음)";
        }
        return list.stream()
                .map(e -> e instanceof Map<?, ?> m
                        ? "- " + m.get("file") + ":" + m.get("line") + " " + m.get("note")
                        : "- " + e)
                .collect(Collectors.joining("\n"));
    }
}
