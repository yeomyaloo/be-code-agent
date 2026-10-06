# be-code-agent

소스코드를 탐색해 보안 취약점을 찾는 LLM 에이전트 (ARTEX 구조 참고, 코드는 새로 작성).

## 요구 사항

- JDK 17 이상으로 Gradle 실행 (JDK 21은 Gradle toolchain이 자동으로 받아옴)
- PostgreSQL 15 이상

## DB 준비

PostgreSQL 설치 후 `psql -U postgres`로 접속해서:

```sql
create user codeagent with password 'codeagent';
create database codeagent owner codeagent;
```

접속 정보는 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 바꿀 수 있음.
테이블은 애플리케이션 시작 시 Flyway(`src/main/resources/db/migration`)가 만듦.

## 실행

```
./gradlew bootRun
```

## 패키지 구조

| 패키지 | 역할 |
|---|---|
| `project` | 분석 대상 저장소, 분석 작업(Job) |
| `codegraph` | 코드 그래프 (파일·클래스·메서드·진입점·위험 지점, 호출 관계) |
| `analysisgraph` | 분석 그래프 (goal/intention/fact/finding/hint), 연결점(anchor), 실행 담당 기록 |
| `api` | REST API |

## 코드 그래프 API

```
# 1. 분석할 저장소 등록 (경로는 슬래시로)
curl -X POST localhost:8080/api/projects -H "Content-Type: application/json" \
     -d '{"name":"animealth-backend","repoPath":"C:/path/to/animealth-backend"}'

# 2. 코드 그래프 만들기 (다시 호출하면 지우고 새로 만듦)
curl -X POST localhost:8080/api/projects/1/index

# 3. 조회
curl localhost:8080/api/projects/1/code-graph/entry-points   # HTTP 진입점
curl localhost:8080/api/projects/1/code-graph/sinks          # 위험 지점
curl localhost:8080/api/projects/1/code-graph/sink-paths     # 진입점 → 위험 지점 호출 경로
curl localhost:8080/api/projects/1/code-graph/nodes/{id}/callers
curl localhost:8080/api/projects/1/code-graph/nodes/{id}/callees
```

위험 지점 규칙은 `codegraph/ingest/SinkRules.java`에 있음.
