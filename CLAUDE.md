# cotcha-net

Java/Spring 소스코드를 정적 파싱해 코드 그래프를 만들고, Claude 에이전트(Worker → Verifier)가 진입점 → 위험 지점 경로를 조사해 보안 취약점을 찾는 백엔드. ARTEX의 구조(이중 그래프, Planner/Worker)를 참고했다.

## 명령

```bash
./gradlew bootRun     # 실행 (localhost:8080, PostgreSQL 필요)
./gradlew test        # 전체 테스트
./gradlew test --tests '*BenchmarkSinkTest'      # DB 없이 도는 테스트만
./gradlew test -PcoreCodeAgentDir=../cotcha-trail   # 공용 엔진을 로컬 소스로 (composite build)
python -m unittest discover -s benchmark         # 벤치마크 채점 스크립트 테스트
python benchmark/run.py --runs 3                 # 실제 LLM 으로 벤치마크 채점 (서버·API 키 필요, 수동)
```

- Windows PowerShell에서는 `.\gradlew.bat`.
- DB가 필요한 테스트: `CodeAgentApplicationTests`, `CodeGraphReindexTest`, `AnalysisFlowTest`, `PlannerFlowTest`, `ApprovalFlowTest`, `ReportTest`, `AnalysisListTest`, `AnalysisEventsTest`, `DependencyScanFlowTest`. API 키는 어떤 테스트에도 필요 없다.
- 테스트는 `test` 프로필(build.gradle 이 켬)로 돌아서 개발 DB의 `public` 스키마가 아니라 `codeagent_test` 스키마를 쓴다(`src/test/resources/application-test.yml`). 스프링 컨텍스트가 뜰 때마다 그 스키마를 비우고 다시 만든다(`TestDatabaseConfig`). 다른 DB 를 쓰려면 `TEST_DB_URL`.
- API 키와 DB 비밀번호는 `src/main/resources/application-local.yml`(git 제외)에 둔다. 예시는 `application-local.yml.example`.

## 구조

| 패키지 | 역할 |
|---|---|
| `codegraph` | 코드 그래프 저장·조회(Java/Spring, Kotlin, Python/FastAPI·Flask·Django, Node.js·Go·PHP·Ruby·C# 백엔드, 프론트엔드. Java ↔ Kotlin 호출은 엔진이 잇는다). 색인할 때 판별한 언어로 `Project.language` 를 바꾼다. 파싱은 공용 라이브러리 cotcha-trail(`com.codeagent.core`)의 `CodeGraphEngine.analyze()`, `ingest/CodeGraphIndexer` 가 그 결과를 DB 에 옮기는 어댑터, `query/` 조회 |
| `analysisgraph` | 분석 그래프 (GOAL/INTENTION/FACT/FINDING), anchor, worker_trace, `AnalysisEvents`(SSE: 그래프·기록이 바뀌면 change, 작업이 끝나면 finished. 쓰기 경로에서 `events.changed()` 를 부른다) |
| `agent` | `AgentLoop`(Claude 도구 호출 반복), 프롬프트(Planner·Worker·Verifier), `tool/` 도구 (Worker 8, Planner 전용 7, Verifier 판정 1) |
| `dependency` | 빌드 파일(pom.xml, build.gradle, libs.versions.toml, gradle.lockfile)의 라이브러리 → OSV 알려진 취약점. OSV 요청엔 좌표·버전만 보낸다. 검사마다 `dependency_scan` 에 저장하고 지난 검사와 비교(`DependencyDiff`) |
| `orchestrator` | `AnalysisService`(Planner 회차 ↔ Worker 실행 반복, `PlannerMode` llm/paths), `VerificationService`(발견 재검증) |
| `llm` | Anthropic 클라이언트, 역할별 모델 설정 |
| `api` | REST 컨트롤러 |

- Claude 호출은 `AgentLoop.run()` 한 곳에서만 한다. 새 역할은 `AgentLoop`에 프롬프트와 도구 목록만 다르게 넘겨 만든다.
- Planner 의 `ToolContext.intentionId` 는 목표(GOAL) 노드 id 다. 회차·상한·종료 여부는 목표 props(`plannerRound`, `maxIntentions`, `planningFinished`)에 둔다.
- 분석 작업은 시작할 때 `Project.currentCommit()`(Git 주소면 받은 커밋, 폴더면 `.git` HEAD)을 `analysis_job.commit_sha` 에 남긴다. 보고서는 작업의 커밋을 쓴다.
- 사람 승인: 목표 props `requireApproval`, `approvedRound`, `approvals`. 승인이 필요하면 `AnalysisService.pauseForApproval` 이 작업을 `WAITING_APPROVAL` 로 두고 스레드를 끝낸다. `approve()` 가 `advance()` 로 이어서 진행한다. 상태 전이는 `AnalysisJobRepository.transition`(조건부 update)으로만 한다.
- 새 도구는 `AgentTool`을 구현한 `@Component`로 추가하고, 쓸 역할의 화이트리스트(`AnalysisService.WORKER_TOOLS`/`PLANNER_TOOLS`, `VerificationService.VERIFIER_TOOLS`)에 이름을 넣는다. 입력 오류는 `ToolInputException`으로 던진다(Claude에게 오류로 전달됨). `AgentLoop` 가 실행 전에 `ToolArgsCheck` 로 properties·required 와 맞춰 보므로(모르는 인자·빠진 필수·enum·정수·배열 항목), 도구 안에서는 그 밖의 검사만 한다.
- 파일 접근 도구는 반드시 `RepoPaths.resolve()`로 경로를 검사한다(저장소 밖 접근 차단).
- 위험 지점 규칙(`rules/SinkRules.java`, 새 분류는 `CATEGORY_ORDER` 에도), 진입점 탐지(`java/EntryPointDetector.java`), 프론트엔드(JS/TS/Vue/HTML) 규칙(`frontend/FrontendScanner.java`, 줄 단위 정규식, 비밀값은 snippet 에서 가린다)은 cotcha-trail 에 있다. 고치면 core 버전을 올리고 이 저장소와 cotcha-pocket 의 의존 버전을 같이 맞춘다. 엔진은 GitHub Packages 에서 받으므로 `gpr.user`/`gpr.key`(또는 `GITHUB_ACTOR`/`GITHUB_TOKEN`)가 필요하다. 프론트엔드 위험 지점은 props `frontend=true` 이고 진입점 경로가 없다.
- 평가 하네스는 `benchmark/`. 정답 파일(`shop-app.expected.json`)은 분석 대상 폴더 밖에 둔다(에이전트가 읽으면 안 됨). 벤치마크 앱에 힌트 주석을 달지 않는다. 위험 지점 규칙을 바꾸면 `BenchmarkSinkTest` 가 정답의 위험 지점 목록과 정확히 같은지 본다. 앱을 고치면 정답 파일의 줄 번호도 고친다.
- 테스트는 실제 OSV 에 요청하지 않는다. `OsvClientTest` 처럼 로컬 HttpServer 로 흉내 낸다.

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
- README는 영어 먼저, `---` 아래에 한국어. 둘 다 같이 고친다.
- ARTEX(AGPL-3.0)의 코드나 프롬프트는 복사하지 않는다. 아이디어만 참고한다.
- 비밀값(API 키, 토큰, 비밀번호)은 코드나 `application.yml`에 쓰지 않는다.
- 분석 대상 코드는 읽기만 한다. 빌드하거나 실행하지 않는다.
