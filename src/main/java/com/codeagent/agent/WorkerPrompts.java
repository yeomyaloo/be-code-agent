package com.codeagent.agent;

import com.codeagent.codegraph.query.CodeGraphQuery.CodeNodeView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;

import java.util.stream.Collectors;

/**
 * 실행 담당 프롬프트.
 * 시스템 프롬프트는 모든 할 일에 같은 내용이라 캐시되고, 할 일마다 바뀌는 내용은 사용자 메시지로 보낸다.
 */
public final class WorkerPrompts {

    public static final String SYSTEM = """
            당신은 Java/Spring 백엔드 소스코드를 감사하는 보안 분석가입니다.
            맡은 할 일 하나(진입점 → 위험 지점 호출 경로 하나)를 조사해서, 외부 사용자가 이 경로로 실제 공격할 수 있는지 판단합니다.

            조사 방법
            - 경로상의 메서드를 read_file로 직접 읽고, 사용자 입력이 어떤 파라미터로 들어와 어떻게 바뀌며 위험 지점에 닿는지 따라갑니다.
            - 코드 그래프는 정적 분석 결과라 틀릴 수 있습니다. 호출 관계가 의심스러우면 get_callers/get_callees와 grep_code로 확인합니다.
            - 입력 검증, 이스케이프, 파라미터 바인딩(PreparedStatement의 ?, JPA 파라미터), 허용 목록, 권한 검사처럼 공격을 막는 장치가 있는지 반드시 확인합니다.
              Spring Security 설정, @Valid 같은 Bean Validation, 필터·인터셉터도 확인 대상입니다.
            - 입력이 상수나 서버 내부 값뿐이라면 공격 불가능으로 판단합니다.
            - 작업 초반에 search_worker_traces로 다른 실행 담당이 같은 코드를 이미 분석했는지 확인하면 중복을 줄일 수 있습니다.

            기록
            - 코드에서 직접 확인한 사실은 record_fact로 남깁니다. 다른 실행 담당과 검증 담당이 이 기록을 봅니다.
            - 외부 입력이 막힘 없이 위험 지점까지 간다는 것을 확인했을 때만 record_finding으로 발견을 남기고, 근거 fact와 코드 위치를 연결합니다.
            - 막는 장치가 있거나 입력이 닿지 않으면 발견을 남기지 말고, 그 이유를 사실로 기록합니다.

            마무리
            조사가 끝나면 도구를 더 부르지 말고 다음 형식으로 한국어로 답합니다.
            판정: 취약 / 취약하지 않음 / 판단 불가
            근거: 핵심 이유 몇 줄 (파일:줄 포함)
            """;

    private WorkerPrompts() {
    }

    public static String intention(SinkPathView path) {
        String hops = path.path().stream()
                .map(WorkerPrompts::hop)
                .collect(Collectors.joining("\n"));
        return """
                할 일: 아래 진입점의 외부 입력이 위험 지점까지 도달해 악용될 수 있는지 조사하세요.

                진입점: %s %s  (노드 id %d)
                처리 메서드: %s

                위험 지점: [%s %s] %s  (노드 id %d)
                위치: %s:%s
                코드: %s

                코드 그래프상 호출 경로:
                %s
                """.formatted(
                path.entryPoint().httpMethod(), path.entryPoint().path(), path.entryPoint().id(),
                path.entryPoint().handler(),
                path.sink().category(), path.sink().cwe(), path.sink().api(), path.sink().id(),
                path.sink().filePath(), path.sink().line(), path.sink().snippet(),
                hops);
    }

    private static String hop(CodeNodeView node) {
        String location = node.filePath() == null ? "" : "  (" + node.filePath() + ":" + node.line() + ")";
        return "- [" + node.id() + "] " + node.kind() + " " + node.qualifiedName() + location;
    }
}
