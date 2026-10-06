# be-code-agent

**소스코드를 탐색해 보안 취약점을 찾는 LLM 멀티 에이전트 코드 보안 분석 시스템**

be-code-agent는 저장소 소스코드를 정적으로 파싱해 **코드 그래프**를 만들고, 그 위에서 LLM 에이전트가 "사용자 입력이 위험한 API까지 도달하는가"를 추적해 취약점을 찾고 검증하는 것을 목표로 한다.
아키텍처는 [ARTEX](https://github.com/Autumn-27/ARTEX)의 이중 그래프 + Planner/Worker 구조를 참고했고, 코드는 모두 새로 작성했다. ARTEX가 **살아있는 서버**를 탐색한다면, be-code-agent는 **소스코드**를 탐색한다.

> 현재 단계: **코드 그래프 생성·조회까지 구현됨.** 분석 그래프는 DB 스키마와 엔티티만 있고, Planner/Worker 에이전트 루프는 아직 구현 전이다. 자세한 내용은 [진행 상황](#진행-상황)을 참고.

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

### 에이전트 분석 (예정)

- Planner가 아직 검사하지 않은 진입점·경로에 대해서만 분석 할 일(intention)을 만들고, 여러 Worker가 나눠서 조사
- 데이터 흐름 증거를 붙여 발견(finding)을 기록하고, 별도 검증 단계에서 오탐을 걸러냄
- SARIF / HTML 보고서 내보내기

---

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 프로젝트 뼈대, DB 스키마 (코드 그래프 + 분석 그래프) | ✅ 완료 |
| 2 | 코드 그래프 생성 (Java/Spring 파싱, 진입점·위험 지점·호출 관계) | ✅ 완료 |
| 3 | 코드 그래프 조회 API (진입점, 위험 지점, 호출 경로, 호출자/피호출자) | ✅ 완료 |
| 4 | LLM 연동, Worker 도구(`read_file`, `get_callers`, `trace_dataflow` 등) | ⬜ 예정 |
| 5 | Planner/Worker 실행 루프, 공유 할 일 목록 | ⬜ 예정 |
| 6 | 발견 검증(오탐 제거), 증거 수집 | ⬜ 예정 |
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

### 2. 실행

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

- JPA는 `ddl-auto: validate`라서 스키마 변경은 반드시 Flyway 마이그레이션 파일(`V2__...sql`)로 한다.
- 가상 스레드(`spring.threads.virtual.enabled`)가 켜져 있다.

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

다시 호출하면 기존 그래프를 지우고 새로 만든다. 응답에 파일·클래스·메서드·진입점·위험 지점 개수, 호출 관계 수, 타입을 알 수 없었던 호출 수, 파싱 오류 목록, 걸린 시간이 담긴다.

### 3. 조회

| API | 설명 |
|---|---|
| `GET /api/projects` | 등록된 저장소 목록 |
| `GET /api/projects/{id}/code-graph/entry-points` | HTTP 진입점 |
| `GET /api/projects/{id}/code-graph/sinks` | 위험 지점 (분류, CWE, 코드 조각, 위치) |
| `GET /api/projects/{id}/code-graph/sink-paths?maxDepth=12` | 진입점 → 위험 지점 호출 경로 (최대 깊이 30, 최대 500개) |
| `GET /api/projects/{id}/code-graph/nodes/{nodeId}/callers` | 이 메서드를 호출하는 메서드 |
| `GET /api/projects/{id}/code-graph/nodes/{nodeId}/callees` | 이 메서드가 호출하는 메서드·위험 지점 |

---

## 개발

```bash
./gradlew test      # 테스트
./gradlew build     # 빌드
```

- `CodeGraphBuilderTest`는 DB 없이 돈다. 일부러 취약하게 만든 예제 앱(`src/test/resources/fixtures/vulnerable-app`)을 파싱해서 진입점·위험 지점·상속 연결·도달 경로를 검증한다.
- `CodeAgentApplicationTests`는 스프링 컨텍스트를 띄우므로 PostgreSQL이 떠 있어야 한다.
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
- **분석 그래프**는 분석 작업(`analysis_job`)마다 새로 만든다. 노드 상태는 `OPEN → CLAIMED → DONE`, 발견은 `CONFIRMED / REJECTED`.
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
CodeGraphIndexer           기존 그래프 삭제 후 JdbcTemplate 배치 insert (500건 단위)
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
│   │   │   └── CodeGraphIndexer.java                DB 저장 (재생성)
│   │   └── query/CodeGraphQuery.java                조회, 재귀 CTE 경로 탐색
│   ├── analysisgraph/domain/                        AnalysisNode/Edge, Anchor, WorkerTrace
│   ├── api/                                         REST 컨트롤러
│   └── common/GlobalExceptionHandler.java           404 / 400 응답 변환
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__init.sql                    전체 스키마
└── src/test/
    ├── java/.../CodeGraphBuilderTest.java           코드 그래프 테스트 (DB 불필요)
    └── resources/fixtures/vulnerable-app/           테스트용 취약 예제 앱
```

---

## 참고

- [ARTEX](https://github.com/Autumn-27/ARTEX): 이중 그래프, Planner/Worker, 연결점(anchor), Worker 기록 공유 구조를 참고함. ARTEX는 AGPL-3.0이며, 이 저장소는 ARTEX 코드를 포함하지 않는다.
- [JavaParser](https://javaparser.org/)
- [CWE](https://cwe.mitre.org/)

---

## 면책 조항

- 이 도구는 **본인이 소유했거나 분석 권한을 받은 소스코드**의 보안 점검용이다.
- 분석 결과는 정적 분석과 LLM 판단에 기반하므로 오탐·미탐이 있을 수 있다. 최종 판단은 사람이 검토해서 내려야 한다.
