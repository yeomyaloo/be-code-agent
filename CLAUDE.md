# be-code-agent

Java/Spring 소스코드를 정적 파싱해 코드 그래프를 만들고, Claude 에이전트(Worker → Verifier)가 진입점 → 위험 지점 경로를 조사해 보안 취약점을 찾는 백엔드. ARTEX의 구조(이중 그래프, Planner/Worker)를 참고했다.

## 명령

```bash
./gradlew bootRun     # 실행 (localhost:8080, PostgreSQL 필요)
./gradlew test        # 전체 테스트
./gradlew test --tests '*CodeGraphBuilderTest'   # DB 없이 도는 테스트만
```

- Windows PowerShell에서는 `.\gradlew.bat`.
- DB가 필요한 테스트: `CodeAgentApplicationTests`, `CodeGraphReindexTest`, `AnalysisFlowTest`. API 키는 어떤 테스트에도 필요 없다.
- API 키와 DB 비밀번호는 `src/main/resources/application-local.yml`(git 제외)에 둔다. 예시는 `application-local.yml.example`.

## 구조

| 패키지 | 역할 |
|---|---|
| `codegraph` | 소스 파싱 → 코드 그래프 (LLM 사용 안 함). `ingest/` 생성, `query/` 조회 |
| `analysisgraph` | 분석 그래프 (GOAL/INTENTION/FACT/FINDING), anchor, worker_trace |
| `agent` | `AgentLoop`(Claude 도구 호출 반복), 프롬프트, `tool/` 도구 9개 |
| `orchestrator` | `AnalysisService`(할 일 생성 + Worker 실행), `VerificationService`(발견 재검증) |
| `llm` | Anthropic 클라이언트, 역할별 모델 설정 |
| `api` | REST 컨트롤러 |

- Claude 호출은 `AgentLoop.run()` 한 곳에서만 한다. 새 역할은 `AgentLoop`에 프롬프트와 도구 목록만 다르게 넘겨 만든다.
- 새 도구는 `AgentTool`을 구현한 `@Component`로 추가한다. 입력 오류는 `ToolInputException`으로 던진다(Claude에게 오류로 전달됨).
- 파일 접근 도구는 반드시 `RepoPaths.resolve()`로 경로를 검사한다(저장소 밖 접근 차단).
- 위험 지점 규칙은 `codegraph/ingest/SinkRules.java`, 진입점 탐지는 `EntryPointDetector.java`.

## 코드 규칙

- 주석, 로그, 예외 메시지, 프롬프트, 테스트 메서드 이름은 한국어.
- DTO와 값 객체는 record. 엔티티는 Lombok `@Getter` + `@NoArgsConstructor(access = PROTECTED)`.
- 그래프 탐색과 대량 저장은 `JdbcTemplate`(재귀 CTE, 배치). 단순 CRUD는 JPA.
- 스키마 변경은 새 Flyway 파일(`V{n}__설명.sql`)로만 한다. `ddl-auto: validate`라서 엔티티와 스키마가 맞아야 뜬다. 기존 마이그레이션 파일은 고치지 않는다.
- JSON은 Jackson 3(`tools.jackson.*`)을 쓴다.

## 작업 규칙

- 사용자에게 설명과 요약은 한국어로 한다.
- 작업이 끝나고 테스트가 통과하면 main에 커밋하고 푸시까지 해도 된다. 내가 만들지 않은 변경 파일은 커밋에 섞지 않는다.
- 기능을 바꾸면 README의 진행 상황, API 표, 디렉터리 구조, `docs/images/*.svg` 구조 그림도 같이 갱신한다.
- ARTEX(AGPL-3.0)의 코드나 프롬프트는 복사하지 않는다. 아이디어만 참고한다.
- 비밀값(API 키, 토큰, 비밀번호)은 코드나 `application.yml`에 쓰지 않는다.
- 분석 대상 코드는 읽기만 한다. 빌드하거나 실행하지 않는다.
