# be-code-agent

**소스코드를 탐색해 보안 취약점을 찾는 LLM 멀티 에이전트 코드 보안 분석 시스템**

be-code-agent는 저장소 소스코드를 정적으로 파싱해 **코드 그래프**를 만들고, 그 위에서 LLM 에이전트가 "사용자 입력이 위험한 API까지 도달하는가"를 추적해 취약점을 찾고 검증하는 것을 목표로 한다.
아키텍처는 [ARTEX](https://github.com/Autumn-27/ARTEX)의 이중 그래프 + Planner/Worker 구조를 참고했고, 코드는 모두 새로 작성했다. ARTEX가 **살아있는 서버**를 탐색한다면, be-code-agent는 **소스코드**를 탐색한다.

> 현재 단계: **코드 그래프 생성·조회, Claude 연동 Worker 에이전트 루프, 발견 검증(Verifier)까지 구현됨.** 할 일은 코드 그래프의 진입점 → 위험 지점 경로에서 기계적으로 만들고 Worker 하나가 차례로 처리한 뒤, Verifier가 발견을 하나씩 다시 확인한다. LLM Planner, 병렬 Worker는 아직 구현 전이다. 자세한 내용은 [진행 상황](#진행-상황)을 참고.

---

## 목차

- [주요 기능](#주요-기능)
- [진행 상황](#진행-상황)
- [설치](#설치)
- [설정](#설정)
- [사용법](#사용법)
- [개발](#개발)
- [시스템 기술 구조](#시스템-기술-구조)
- [디렉터리 구조](#디렉터리-구조)
- [참고](#참고)
- [사용 기술](#사용-기술)
- [면책 조항](#면책-조항)

---

## 주요 기능

### 코드 그래프 생성 (구현됨)

- **라이브러리 jar 없이 소스만으로 분석**: import 문과 변수·필드 선언으로 타입을 알아내고, 안 되면 JavaParser symbol solver에 맡긴다. 빌드하지 않은 저장소도 바로 분석할 수 있다.
- **구조 추출**: 파일 → 클래스 → 메서드 계층과 상속 관계(인터페이스 메서드 → 구현 메서드)를 만든다.
- **HTTP 진입점 탐지**: `@RestController`/`@Controller`의 `@GetMapping`·`@PostMapping`·`@RequestMapping` 등을 읽어 `GET /api/users/search` 형태로 정리한다. 클래스 레벨 경로, 여러 경로, `method = {GET, HEAD}`도 처리한다.
- **위험 지점(Sink) 탐지**: 9개 분류 30여 개 규칙으로 위험한 API 호출 위치를 찾는다.

  | 분류 | CWE | 대표 API |
  |---|---|---|
  | SQL | CWE-89 | `Statement.execute*`, `JdbcTemplate.query*`, `EntityManager.createNativeQuery` |
  | COMMAND | CWE-78 | `Runtime.exec`, `new ProcessBuilder` |
  | PATH | CWE-22 | `new File`, `Paths.get`, `Files.read*/write*` |
  | DESERIALIZATION | CWE-502 | `ObjectInputStream.readObject`, `XStream.fromXML`, `Yaml.load` |
  | SSRF | CWE-918 | `new URL`, `RestTemplate.*` |
  | CODE_INJECTION | CWE-94 | `ScriptEngine.eval` |
  | EXPRESSION_INJECTION | CWE-917 | `SpelExpressionParser.parseExpression` |
  | JNDI | CWE-74 | `InitialContext.lookup` |
  | OPEN_REDIRECT | CWE-601 | `HttpServletResponse.sendRedirect` |

- **진입점 → 위험 지점 경로 탐색**: PostgreSQL 재귀 CTE로 "어느 API에서 어느 위험 지점까지 어떤 메서드들을 거쳐 도달하는지"를 뽑는다. 인터페이스를 거치는 호출도 따라간다.

### 에이전트 분석 (일부 구현됨)

- **할 일 생성**: 진입점 → 위험 지점 경로마다 (진입점, 위험 지점) 쌍 하나당 가장 짧은 경로로 할 일(INTENTION)을 만든다.
- **Worker 에이전트 루프**: Claude가 도구를 호출하며 "외부 입력이 막힘 없이 위험 지점까지 가는가"를 코드로 확인하고, 판정(취약 / 취약하지 않음 / 판단 불가)과 근거를 남긴다. 모든 단계는 `worker_trace`에 기록된다.
- **Worker 도구 8개**

  | 도구 | 하는 일 |
  |---|---|
  | `read_file` | 저장소 파일을 줄 번호와 함께 읽음 (한 번에 최대 400줄, 저장소 밖 경로 차단) |
  | `grep_code` | 정규식으로 코드 검색 (최대 80건) |
  | `find_code_nodes` | 코드 그래프 노드를 이름으로 찾기 |
  | `get_callers` / `get_callees` | 호출하는 쪽 / 호출 대상 (인터페이스 ↔ 구현 포함) |
  | `search_worker_traces` | 같은 분석 작업의 다른 Worker 기록 검색 |
  | `record_fact` | 코드에서 확인한 사실 기록 |
  | `record_finding` | 취약점 발견 기록 (근거 코드 위치 필수, 검증 대기 상태로 저장) |

- **멈춤 조건**: 할 일당 최대 단계 수, 분석 작업 토큰 예산, 응답 잘림, 모델 거절, 컨텍스트 초과. 첫 호출부터 실패하면(인증 오류 등) 작업 전체를 멈춘다.
- **발견 검증 (Verifier)**: Worker가 할 일을 다 끝내면, 검증 대기(`OPEN`) 발견마다 별도의 에이전트(기본 Claude Opus 5.5)가 보고서를 믿지 않고 코드를 다시 읽어 판정한다.
  - 확인 항목: 입력이 정말 외부에서 오는지, 진입점에서 실제로 도달하는지, 방어 장치(입력 검증, 파라미터 바인딩, 권한 검사 등)가 막는지, 심각도가 과장되지 않았는지
  - 도구: 읽기 전용 6개(`read_file`, `grep_code`, `find_code_nodes`, `get_callers`, `get_callees`, `search_worker_traces`) + `submit_verdict`
  - 판정: `CONFIRMED`(공격 가능) / `REJECTED`(오탐) / `UNCERTAIN`(근거 부족). 판정 근거·확인한 방어 장치·재평가한 심각도는 발견의 `props.verification`에 저장된다.
  - Verifier가 판정을 제출하지 못하고 끝나면(단계·예산 초과, 오류) `UNCERTAIN`으로 남긴다.
- **예정**: LLM Planner가 아직 검사하지 않은 경로만 골라 할 일 배포, 여러 Worker 병렬 실행, SARIF / HTML 보고서

---

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 프로젝트 뼈대, DB 스키마 (코드 그래프 + 분석 그래프) | ✅ 완료 |
| 2 | 코드 그래프 생성 (Java/Spring 파싱, 진입점·위험 지점·호출 관계) | ✅ 완료 |
| 3 | 코드 그래프 조회 API (진입점, 위험 지점, 호출 경로, 호출자/피호출자) | ✅ 완료 |
| 4 | LLM 연동(Anthropic Java SDK), Worker 도구 8개, Worker 에이전트 루프 | ✅ 완료 (실제 API 호출 검증은 API 키 등록 후) |
| 5 | 공유 할 일 목록(`SKIP LOCKED`)과 분석 작업 API | ✅ 완료 |
| 5-1 | LLM Planner, Worker 병렬 실행 | ⬜ 예정 |
| 6 | 발견 검증(Verifier, 오탐 제거) | ✅ 완료 (실제 API 호출 검증은 API 키 등록 후) |
| 7 | 보고서 (SARIF, HTML) | ⬜ 예정 |
| 8 | 웹 UI, 실시간 진행 상황(SSE), 사람 승인 단계 | ⬜ 예정 |

---

## 설치

### 요구 사항

- **JDK 17 이상**으로 Gradle 실행 (빌드에 쓰는 JDK 21은 Gradle toolchain이 자동으로 받아옴)
- **PostgreSQL 15 이상**

### 1. DB 준비

PostgreSQL 설치 후 `psql -U postgres`로 접속해서:

```sql
create user codeagent with password 'codeagent';
create database codeagent owner codeagent;
```

테이블은 애플리케이션 시작 시 Flyway(`src/main/resources/db/migration`)가 자동으로 만든다.

### 2. Anthropic API 키 등록

에이전트 분석(`/analyses`)에만 필요하다. 코드 그래프 생성·조회는 키 없이 된다.

1. [Anthropic Console](https://console.anthropic.com)에서 API 키를 발급받는다.
2. 견본 파일을 복사해서 `application-local.yml`을 만든다.

   ```bash
   cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
   ```

3. `application-local.yml`에 키를 적는다.

   ```yaml
   codeagent:
     llm:
       api-key: sk-ant-...
   ```

`application-local.yml`은 `.gitignore`에 들어 있어서 git에 올라가지 않는다. **키를 `application.yml`에 직접 적지 말 것.** 자세한 내용은 [비밀 값 관리](#비밀-값-관리) 참고.

### 3. 실행

```bash
./gradlew bootRun
```

Windows PowerShell에서는 `.\gradlew.bat bootRun`.
기본 주소는 `http://localhost:8080`.

---

## 설정

설정 파일은 `src/main/resources/application.yml`. 아래 환경 변수로 덮어쓸 수 있다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/codeagent` | DB 접속 URL |
| `DB_USERNAME` | `codeagent` | DB 사용자 |
| `DB_PASSWORD` | `codeagent` | DB 비밀번호 |
| `ANTHROPIC_API_KEY` | (없음) | Anthropic API 키 (`codeagent.llm.api-key`) |

- JPA는 `ddl-auto: validate`라서 스키마 변경은 반드시 Flyway 마이그레이션 파일(`V3__...sql`처럼 다음 번호)로 한다. 이미 적용된 마이그레이션 파일은 고치지 않는다.
- 가상 스레드(`spring.threads.virtual.enabled`)가 켜져 있다.

### 비밀 값 관리

API 키·비밀번호 같은 비밀 값은 `application.yml`에 **자리만** 두고, 실제 값은 git에 올라가지 않는 곳에 둔다.

| 파일 / 위치 | 내용 | git |
|---|---|---|
| `src/main/resources/application.yml` | `api-key: ${ANTHROPIC_API_KEY:}` 처럼 자리만 있음 | 올라감 |
| `src/main/resources/application-local.yml` | 개인 PC의 실제 값 (앱 시작 시 자동으로 읽음) | **안 올라감** |
| `src/main/resources/application-local.yml.example` | 위 파일의 견본 | 올라감 |
| 환경 변수 | 서버 배포 시 권장 | - |

값을 읽는 순서는 `application-local.yml` → 환경 변수다. `application-local.yml`에 `codeagent.llm.api-key`가 있으면 그 값을 쓰고, 없으면 환경 변수 `ANTHROPIC_API_KEY`를 쓴다.

`.gitignore`는 이 밖에도 `.env`, `*.pem`·`*.key` 같은 인증서 파일, `application-secret.yml`, 로그 파일을 제외한다.

### LLM 설정

`application.yml`의 `codeagent.llm`, `codeagent.agent`에서 바꾼다.

| 설정 | 기본값 | 설명 |
|---|---|---|
| `codeagent.llm.worker.model` | `claude-sonnet-5-5` | Worker가 쓰는 모델 |
| `codeagent.llm.worker.effort` | `medium` | Worker 추론 깊이 (`low` / `medium` / `high` / `xhigh` / `max`) |
| `codeagent.llm.verifier.*` | `claude-opus-5-5`, `high` | Verifier가 쓰는 모델·추론 깊이 |
| `codeagent.llm.planner.*` | `claude-opus-5-5`, `high` | Planner용 (아직 사용하는 코드 없음) |
| `codeagent.llm.max-tokens` | `16000` | 응답 하나의 최대 출력 토큰 |
| `codeagent.llm.fallbacks` | `true` | 모델이 안전 분류기로 요청을 거절하면 서버가 다른 모델로 자동 재시도 |
| `codeagent.agent.max-steps` | `40` | 할 일 하나에 쓸 수 있는 최대 LLM 호출 횟수 |

시스템 프롬프트(`agent/WorkerPrompts.java`, `agent/VerifierPrompts.java`)는 할 일마다 같아서 프롬프트 캐시가 걸려 있다.

---

## 사용법

### 1. 분석할 저장소 등록

경로는 슬래시(`/`)로 적는다.

```bash
curl -X POST localhost:8080/api/projects -H "Content-Type: application/json" \
     -d '{"name":"animealth-backend","repoPath":"C:/path/to/animealth-backend"}'
```

`language`를 생략하면 `java`로 등록된다.

### 2. 코드 그래프 만들기

```bash
curl -X POST localhost:8080/api/projects/1/index
```

코드가 바뀌었으면 다시 호출하면 된다. 노드는 `(종류, 정규화된 이름)`이 같으면 id를 유지하므로, 이미 돌린 분석 결과(연결점)가 그대로 이어진다.

- 파싱 결과에서 사라진 노드는 지운다. 단, 분석 그래프가 참조하는 노드는 `removed_at`만 표시하고 남긴다 (조회 API에서는 빠짐).
- 사라졌던 노드가 다시 나타나면 같은 id로 되살린다.
- 호출 관계(연결선)는 매번 새로 만든다.
- 위험 지점 노드 이름에는 줄 번호가 들어가서, 위쪽 코드가 바뀌어 줄이 밀리면 새 노드가 된다.

응답에 파일·클래스·메서드·진입점·위험 지점 개수, 호출 관계 수, 타입을 알 수 없었던 호출 수, 지운 노드 수(`removedNodes`), 표시만 하고 남긴 노드 수(`retainedNodes`), 파싱 오류 목록, 걸린 시간이 담긴다.

### 3. 조회

| API | 설명 |
|---|---|
| `GET /api/projects` | 등록된 저장소 목록 |
| `GET /api/projects/{id}/code-graph/entry-points` | HTTP 진입점 |
| `GET /api/projects/{id}/code-graph/sinks` | 위험 지점 (분류, CWE, 코드 조각, 위치) |
| `GET /api/projects/{id}/code-graph/sink-paths?maxDepth=12` | 진입점 → 위험 지점 호출 경로 (최대 깊이 30, 최대 500개) |
| `GET /api/projects/{id}/code-graph/nodes/{nodeId}/callers` | 이 메서드를 호출하는 메서드 |
| `GET /api/projects/{id}/code-graph/nodes/{nodeId}/callees` | 이 메서드가 호출하는 메서드·위험 지점 |

### 4. 에이전트 분석 (API 키 필요)

```bash
# 분석 시작 (백그라운드 실행, 바로 202 응답). 생략 시 할 일 5개, 토큰 예산 200만
curl -X POST localhost:8080/api/projects/1/analyses -H "Content-Type: application/json" \
     -d '{"maxIntentions":2,"budgetTokens":300000}'
```

| API | 설명 |
|---|---|
| `POST /api/projects/{id}/analyses` | 분석 시작. `maxIntentions`(1~100), `budgetTokens`(1만 이상) |
| `GET /api/analyses/{jobId}` | 작업 상태(`RUNNING` / `DONE` / `STOPPED` / `FAILED`), 토큰 사용량, 오류, 분석 그래프 노드 전체 |
| `GET /api/analyses/{jobId}/findings?status=` | 발견 목록 (CWE, 심각도, 확신도, 근거 코드 위치, 검증 결과). `status`로 거르기: `OPEN`(검증 대기) / `CONFIRMED` / `REJECTED` / `UNCERTAIN` |
| `POST /api/analyses/{jobId}/verify` | 검증 대기 발견을 다시 검증 (끝난 작업만, 진행 중이면 400) |
| `GET /api/analyses/{jobId}/traces?intentionId=` | 분석 기록 (Claude 응답, 도구 호출·결과). Worker 기록은 할 일 id, Verifier 기록은 발견 id로 거른다 |

할 일마다 "할 일 #N 결론" FACT 노드에 Worker의 최종 판정과 사용 토큰이 남는다. 확정된 취약점만 보려면 `findings?status=CONFIRMED`.
코드 그래프를 먼저 만들어야 하며, 진입점 → 위험 지점 경로가 하나도 없으면 400을 돌려준다.

---

## 개발

```bash
./gradlew test      # 테스트
./gradlew build     # 빌드
```

- `CodeGraphBuilderTest`는 DB 없이 돈다. 일부러 취약하게 만든 예제 앱(`src/test/resources/fixtures/vulnerable-app`)을 파싱해서 진입점·위험 지점·상속 연결·도달 경로를 검증한다.
- `FileToolsTest`는 DB 없이 돈다. `read_file`·`grep_code` 도구의 범위 읽기, 저장소 밖 경로 차단, 입력 오류 처리를 검증한다.
- `CodeAgentApplicationTests`는 스프링 컨텍스트를 띄우므로 PostgreSQL이 떠 있어야 한다.
- `CodeGraphReindexTest`는 예제 앱을 임시 폴더에 복사해서 파일을 고치고 지우며 다시 만들기를 검증한다 (노드 id 유지, 연결점 보존, 사라진 노드 정리). PostgreSQL이 필요하다.
- `AnalysisFlowTest`는 실제 Claude 대신 가짜 `AgentLoop`가 도구를 직접 호출해서 할 일 생성 → 배분 → 사실·발견 기록 → 검증 판정(확정·오탐·판정 미제출) → 작업 종료 흐름과, Worker·Verifier 도구 구성, 검증 재실행을 검증한다. API 키는 필요 없지만 PostgreSQL이 필요하고, **로컬 `codeagent` DB에 테스트 데이터(프로젝트·분석 작업)를 남긴다.**
- **Worker 도구 추가**: `agent/tool/AgentTool`을 구현한 `@Component`를 만들면 Worker에 자동으로 등록된다. 잘못된 입력은 `ToolInputException`을 던지면 Claude에게 오류로 전달되어 다시 시도하게 된다.
- **위험 지점 규칙 추가**: `codegraph/ingest/SinkRules.java`의 `RULES`에 `rule(타입 FQN, 분류, CWE, 메서드...)`를 추가한다. 생성자는 메서드 이름 `<init>`으로 적는다.
- **진입점 종류 추가**: `codegraph/ingest/EntryPointDetector.java`를 확장한다.

---

## 시스템 기술 구조

### 기술 스택

| 영역 | 기술 |
|---|---|
| 언어 / 프레임워크 | Java 21, Spring Boot 4.1 (Web MVC, Data JPA, Validation) |
| 빌드 | Gradle 9.7 |
| DB | PostgreSQL + Flyway 마이그레이션, 그래프 탐색은 재귀 CTE |
| 코드 파싱 | JavaParser 3.26 + symbol solver |
| LLM | Anthropic Java SDK 2.68 (Worker: Claude Sonnet 5.5, Planner·Verifier: Claude Opus 5.5) |
| 기타 | Lombok, 가상 스레드 |

### 핵심 개념: 이중 그래프

ARTEX의 "자산 그래프 + 탐색 그래프" 구조를 코드 분석에 맞게 바꿨다.

```
 ┌──────────────────────────────┐            ┌──────────────────────────────┐
 │  코드 그래프 (프로젝트 단위)  │            │  분석 그래프 (분석 작업 단위)  │
 │  = ARTEX 자산 그래프          │            │  = ARTEX 탐색 그래프           │
 │                              │            │                              │
 │  FILE ─CONTAINS→ CLASS       │   anchor   │  GOAL ─SPAWNS→ INTENTION     │
 │  CLASS ─CONTAINS→ METHOD     │◄──────────►│  INTENTION ─YIELDS→ FACT     │
 │  ENTRY_POINT ─ROUTES_TO→     │  EXAMINED  │  FACT ─PROVES→ FINDING       │
 │  METHOD ─CALLS→ METHOD/SINK  │  SOURCE    │  HINT, DERIVED_FROM,         │
 │  METHOD ─OVERRIDDEN_BY→      │  SINK      │  DEPENDS_ON                  │
 │                              │ LOCATED_AT │                              │
 └──────────────────────────────┘            └──────────────────────────────┘
```

| ARTEX | be-code-agent |
|---|---|
| 자산 그래프: 도메인 → 서브도메인 → IP → 서비스 → 엔드포인트 | **코드 그래프**: 파일 → 클래스 → 메서드, 진입점, 위험 지점, 호출 관계 |
| 탐색 그래프: goal / intent / fact / finding / hint | **분석 그래프**: GOAL / INTENTION / FACT / FINDING / HINT |
| 연결선: spawns / derived_from / yields / proves | 같은 연결선 + **DEPENDS_ON** (할 일 사이 순서 관계) |
| `exploration_anchors` | **`anchor`**: 분석 노드 ↔ 코드 노드 (역할: EXAMINED, SOURCE, SINK, LOCATED_AT) |
| Worker 실행 기록 검색 (`search_all_worker_traces`) | **`worker_trace`** 테이블 + 전문 검색(GIN) 인덱스 |
| 트래픽 기록 프록시 | (없음, 코드 분석에는 필요 없음) |

- **코드 그래프**는 프로젝트마다 하나씩 유지된다. 노드는 `(종류, 정규화된 이름)`으로 유일하다.
- **분석 그래프**는 분석 작업(`analysis_job`)마다 새로 만든다. 할 일 상태는 `OPEN → CLAIMED → DONE / FAILED`, 발견은 `OPEN`(검증 대기) → `CONFIRMED / REJECTED / UNCERTAIN`.
- **연결점(anchor)** 으로 "이 메서드는 이미 검사했나?", "이 할 일은 어떤 코드를 봤나?"를 양쪽에서 조회할 수 있어서 같은 분석을 반복하지 않는다.

### 코드 그래프 생성 흐름

```
저장소 경로
  │
  ▼
JavaProject.load()         src/main/java 루트를 찾아 .java 파일 파싱 (build, target 등 제외)
  │                        1단계: 프로젝트 타입 이름 수집 → 2단계: 타입·상위 타입·메서드 색인
  ▼
CodeGraphBuilder.build()
  ├─ addStructure()        FILE → CLASS → METHOD (CONTAINS)
  ├─ addOverrides()        상위 타입 메서드 → 구현 메서드 (OVERRIDDEN_BY)
  ├─ addEntryPoints()      EntryPointDetector → ENTRY_POINT (ROUTES_TO)
  └─ addCalls()            메서드 호출 대상 타입 결정 (TypeResolver → symbol solver)
                           ├─ 프로젝트 메서드면 CALLS
                           └─ SinkRules에 맞으면 SINK 노드 + CALLS
  ▼
CodeGraphDraft             DB 저장 전 메모리 그래프 (중복 제거)
  ▼
CodeGraphIndexer           노드 upsert(id 유지) → 사라진 노드 정리 → 연결선 재생성 (JdbcTemplate 배치, 500건 단위)
```

### 현재 에이전트 실행 흐름

```
POST /analyses
  │
  ▼
AnalysisService.start()     진입점 → 위험 지점 경로마다 INTENTION 생성 (GOAL ─SPAWNS→ INTENTION, anchor 연결)
  │                         가상 스레드에서 Worker 시작
  ▼
claimNextIntention()        선행 할 일(DEPENDS_ON)이 끝난 OPEN 할 일 하나를 CLAIMED로 (SELECT ... FOR UPDATE SKIP LOCKED)
  ▼
AgentLoop.run()             Claude 호출 → tool_use면 도구 실행 → tool_result 돌려줌 → 반복
  │                         매 단계 worker_trace 기록, 토큰 사용량 누적
  ▼
결론 FACT 기록, INTENTION을 DONE / FAILED로 → 다음 할 일 (예산 소진 시 작업 STOPPED)
  │
  ▼  할 일이 더 없으면
VerificationService         검증 대기(OPEN) FINDING마다 Verifier 에이전트 실행 (Opus, 읽기 전용 도구 + submit_verdict)
  │                         → CONFIRMED / REJECTED / UNCERTAIN, 판정 근거는 props.verification
  ▼
작업 DONE (검증 중 예산 소진 시 STOPPED, 남은 발견은 POST /verify로 이어서 검증)
```

### 계획 중인 에이전트 실행 구조

ARTEX의 Planner 반복 + Worker 병렬 실행 구조를 그대로 가져갈 계획이다.

```
             [ 사용자 / REST API / (예정) 웹 UI ]
                            │
                      ┌─────▼──────┐
                      │  Planner   │ ← 공유 할 일 목록 (DEPENDS_ON으로 순서 관리)
                      └─────┬──────┘
          아직 검사 안 한 경로만 INTENTION으로 배포
          ┌─────────────┬───┴─────────┬─────────────┐
      ┌───▼───┐     ┌───▼───┐     ┌───▼───┐     ┌───▼───┐
      │Worker1│     │Worker2│     │Worker3│ ... │WorkerN│  (가상 스레드)
      └───┬───┘     └───┬───┘     └───┬───┘     └───┬───┘
          └──── worker_trace로 서로의 분석 기록 검색 ───┘
                            │
               FACT / FINDING 기록 → 그래프 변경 → Planner 다시 실행
                            │
                      ┌─────▼──────┐
                      │  Verifier  │ ← 발견마다 오탐 여부 재검토
                      └────────────┘
```

종료 조건: 새로 검사할 경로가 없거나, 분석 작업의 토큰 예산(`analysis_job.budget_tokens`)을 다 썼을 때.

---

## 디렉터리 구조

```
be-code-agent/
├── build.gradle, settings.gradle, gradlew          Gradle 빌드
├── src/main/java/com/codeagent/
│   ├── CodeAgentApplication.java                    진입점
│   ├── project/                                     분석 대상 저장소(Project), 분석 작업(AnalysisJob)
│   ├── codegraph/
│   │   ├── domain/                                  CodeNode, CodeEdge 엔티티와 종류(enum)
│   │   ├── ingest/                                  코드 그래프 생성
│   │   │   ├── JavaProject.java                     소스 파싱, 타입·메서드 색인
│   │   │   ├── TypeResolver.java                    import 기준 타입 이름 → FQN
│   │   │   ├── EntryPointDetector.java              Spring MVC 진입점 탐지
│   │   │   ├── SinkRules.java, SinkRule.java        위험 지점 규칙
│   │   │   ├── CodeGraphBuilder.java                그래프 조립
│   │   │   ├── CodeGraphDraft.java                  저장 전 메모리 그래프
│   │   │   └── CodeGraphIndexer.java                DB 저장 (노드 id 유지하며 갱신)
│   │   └── query/CodeGraphQuery.java                조회, 재귀 CTE 경로 탐색
│   ├── analysisgraph/
│   │   ├── domain/                                  AnalysisNode/Edge, Anchor, WorkerTrace
│   │   └── AnalysisGraphService.java                분석 그래프 노드·연결선·연결점 기록
│   ├── llm/                                         LLM 설정(LlmProperties, AgentProperties), Anthropic 클라이언트
│   ├── agent/
│   │   ├── AgentLoop.java                           도구 호출 반복 흐름 (Claude ↔ 도구)
│   │   ├── WorkerPrompts.java                       Worker 시스템 프롬프트, 할 일 프롬프트
│   │   ├── VerifierPrompts.java                     Verifier 시스템 프롬프트, 발견 프롬프트
│   │   └── tool/                                    Worker 도구 8개, Verifier 판정 도구, 입력 검증(ToolInput)
│   ├── orchestrator/
│   │   ├── AnalysisService.java                     분석 작업 생성, 할 일 배분, Worker 실행, 검증 단계 시작
│   │   └── VerificationService.java                 검증 대기 발견마다 Verifier 실행
│   ├── api/                                         REST 컨트롤러 (프로젝트, 코드 그래프, 분석)
│   └── common/GlobalExceptionHandler.java           404 / 400 응답 변환
├── src/main/resources/
│   ├── application.yml                              공통 설정 (비밀 값은 자리만)
│   ├── application-local.yml.example                개인 비밀 값 파일 견본
│   └── db/migration/
│       ├── V1__init.sql                             전체 스키마
│       ├── V2__job_error_and_usage.sql              분석 작업 오류·토큰 사용량 칸
│       └── V3__code_node_reindex.sql                코드 노드 indexed_at / removed_at
└── src/test/
    ├── java/.../CodeGraphBuilderTest.java           코드 그래프 테스트 (DB 불필요)
    ├── java/.../CodeGraphReindexTest.java           코드 그래프 재생성 테스트 (DB 필요)
    ├── java/.../FileToolsTest.java                  파일 도구 테스트 (DB 불필요)
    ├── java/.../AnalysisFlowTest.java               분석 흐름 통합 테스트 (DB 필요, API 키 불필요)
    └── resources/fixtures/vulnerable-app/           테스트용 취약 예제 앱
```

---

## 참고

- [ARTEX](https://github.com/Autumn-27/ARTEX): 이중 그래프, Planner/Worker, 연결점(anchor), Worker 기록 공유 구조 등 아키텍처 아이디어만 참고함. ARTEX는 AGPL-3.0이며, 이 저장소는 ARTEX 코드를 포함하지 않는다.

## 사용 기술

- [JavaParser](https://javaparser.org/) (Apache-2.0 / LGPL-3.0 중 선택): Java 소스 파싱, 타입 해석
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) (MIT): Claude API 호출
- [CWE](https://cwe.mitre.org/) (MITRE): 위험 지점과 발견에 붙이는 취약점 분류 번호 (예: CWE-89 SQL 인젝션)

---

## 면책 조항

- 이 도구는 **본인이 소유했거나 분석 권한을 받은 소스코드**의 보안 점검용이다.
- 분석 결과는 정적 분석과 LLM 판단에 기반하므로 오탐·미탐이 있을 수 있다. 최종 판단은 사람이 검토해서 내려야 한다.
- 에이전트 분석은 분석 대상 소스코드 일부를 Anthropic API로 전송한다. 외부로 보내면 안 되는 코드는 분석하지 말 것.
- 에이전트 분석은 Anthropic API 사용량만큼 비용이 든다. `budgetTokens`로 작업당 상한을 정할 수 있다.
