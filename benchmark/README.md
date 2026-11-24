# Benchmark

An evaluation harness that measures how well the agent finds vulnerabilities, and whether it reaches the same conclusions when it analyzes the same code again.

| File | What it is |
|---|---|
| `shop-app/` | A small Spring + React shop with **11 real vulnerabilities** and **10 safe look-alikes** (traps): each vulnerable spot has a safe twin of the same category (bind variable vs. concatenation, allowlisted sort column, normalized path check, hardened XML parser, owner check, fixed redirect, sanitized HTML, …). There are no hint comments in the code. |
| `shop-app.expected.json` | The answer key: file, line range, sink line, accepted CWEs and entry point of each item. It lives **outside** `shop-app/` so the agent cannot read it while analyzing. |
| `score.py` | Scores one or more SARIF reports against the answer key: recall, precision, trap false positives, wrong CWE, duplicates and, for several runs, consistency. |
| `run.py` | Drives a running cotcha-net: registers `shop-app`, builds the code graph, runs the analysis N times, saves each SARIF and scores them. |
| `test_score.py` | Tests for the scoring rules (no LLM). |

## What runs in CI (no API key)

- `BenchmarkSinkTest` (part of `./gradlew test`): every vulnerability **and** every trap in the answer key becomes a sink in the code graph, nothing else does, and each vulnerability is reachable from its entry point. If a rule change makes the graph miss one, the agent would never be asked to look at it, so CI fails.
- `python3 -m unittest discover -s benchmark`: the scoring rules.

## Measuring the LLM (manual, costs tokens)

```bash
./gradlew bootRun                                  # API key in application-local.yml
python benchmark/run.py --runs 3                   # LLM planner, 3 runs
python benchmark/run.py --runs 3 --planner paths   # path planner
```

Results go to `benchmark/results/<time>/` (git-ignored): `run-N.sarif`, `run-N.job.json` (tokens), `score.json`.

To score the Claude Code plugin (cotcha-pocket), run the `security-audit` skill on `benchmark/shop-app`, export the report with `export_report` (`format: sarif`), and score it:

```bash
python benchmark/score.py benchmark/shop-app.expected.json report-1.sarif report-2.sarif
```

### Scoring rules

- A finding points at an item when one of its locations (`locations` or `relatedLocations`) falls inside the item's file and line range.
- Pointing at a vulnerability with an accepted CWE is a hit. Pointing at it with another CWE is a wrong category.
- Pointing at a trap is a trap false positive. Pointing at nothing is another false positive.
- Rejected findings are ignored. `--confirmed-only` counts only findings the Verifier confirmed.
- Consistency: the share of items (vulnerabilities and traps) that got the same outcome in every run, and the mean Jaccard similarity of the detected sets between runs. `--min-recall 0.8` makes the script exit with 1 below that recall.

---

# 벤치마크

에이전트가 취약점을 얼마나 잘 찾는지, 같은 코드를 다시 분석해도 같은 결론을 내는지 재는 평가 하네스다.

| 파일 | 내용 |
|---|---|
| `shop-app/` | 작은 Spring + React 쇼핑몰. **진짜 취약점 11개**와 비슷하게 생겼지만 **안전한 코드 10개**(함정)를 섞었다. 취약한 곳마다 같은 분류의 안전한 짝이 있다(바인드 변수와 문자열 이어 붙이기, 허용 목록으로 거른 정렬 열, normalize 후 경로 확인, 막아 둔 XML 파서, 주인 확인, 고정된 이동 주소, 거른 HTML 등). 코드에 힌트 주석은 없다. |
| `shop-app.expected.json` | 정답 파일. 항목마다 파일, 줄 범위, 위험 지점 줄, 인정하는 CWE, 진입점을 적었다. 에이전트가 분석 중에 읽지 못하도록 `shop-app/` **밖**에 둔다. |
| `score.py` | SARIF 보고서를 정답과 비교해 채점한다. 재현율, 정밀도, 함정 오탐, 분류 틀림, 중복, 그리고 여러 번 돌렸으면 일관성. |
| `run.py` | 실행 중인 cotcha-net 로 `shop-app` 을 등록하고 코드 그래프를 만든 뒤 N번 분석해 SARIF 를 모으고 채점한다. |
| `test_score.py` | 채점 규칙 테스트 (LLM 없음). |

## CI 에서 도는 것 (API 키 필요 없음)

- `BenchmarkSinkTest` (`./gradlew test` 에 포함): 정답의 취약점과 함정이 **모두** 코드 그래프의 위험 지점이 되고 그 밖의 위험 지점은 없으며, 취약점마다 적힌 진입점에서 이어지는지 확인한다. 규칙을 바꾸다 하나라도 놓치면 에이전트가 그 코드를 조사할 일이 아예 생기지 않으므로 CI 가 실패한다.
- `python3 -m unittest discover -s benchmark`: 채점 규칙.

## LLM 성능 재기 (수동, 토큰 비용 있음)

```bash
./gradlew bootRun                                  # application-local.yml 에 API 키
python benchmark/run.py --runs 3                   # LLM 계획, 3번
python benchmark/run.py --runs 3 --planner paths   # 경로 계획
```

결과는 `benchmark/results/<시각>/` 에 남는다(git 제외): `run-N.sarif`, `run-N.job.json`(토큰), `score.json`.

Claude Code 플러그인(cotcha-pocket)을 채점하려면 `benchmark/shop-app` 에 `security-audit` 스킬을 돌리고 `export_report`(`format: sarif`)로 보고서를 받아 채점한다.

```bash
python benchmark/score.py benchmark/shop-app.expected.json report-1.sarif report-2.sarif
```

### 채점 규칙

- 발견의 위치(`locations`, `relatedLocations`) 중 하나가 항목의 파일·줄 범위 안에 있으면 그 항목을 가리킨 것으로 본다.
- 취약점을 가리키고 CWE 가 인정 목록에 있으면 맞힌 것이다. CWE 가 다르면 분류 틀림이다.
- 함정을 가리키면 함정 오탐, 아무것도 가리키지 않으면 기타 오탐이다.
- 기각된 발견은 빼고 센다. `--confirmed-only` 면 Verifier 가 확정한 발견만 센다.
- 일관성: 모든 실행에서 같은 결론이 난 항목(취약점과 함정)의 비율과, 실행끼리 찾은 항목 집합의 평균 자카드 유사도. `--min-recall 0.8` 을 주면 평균 재현율이 그보다 낮을 때 종료 코드 1 로 끝난다.
