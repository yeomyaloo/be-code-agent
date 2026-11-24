#!/usr/bin/env python3
"""실행 중인 cotcha-net 로 벤치마크 앱을 N번 분석하고 SARIF 를 모아 채점한다.

실제 Claude 를 부르므로 서버에 API 키가 설정되어 있어야 하고 토큰 비용이 든다. CI 에서는 돌리지 않는다.

    ./gradlew bootRun                                  # 다른 터미널에서 서버 실행
    python benchmark/run.py --runs 3                   # LLM 계획, 3번
    python benchmark/run.py --runs 3 --planner paths   # 경로 계획

결과는 benchmark/results/<시각>/ 에 run-N.sarif, run-N.job.json, score.json 으로 남는다 (git 제외).
"""
import argparse
import datetime
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import score

HERE = Path(__file__).resolve().parent
FINISHED = {"DONE", "STOPPED", "FAILED"}


def request(base, method, path, body=None, raw=False):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            text = res.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} 실패 ({e.code}): {e.read().decode('utf-8', 'replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"서버에 연결하지 못함 ({base}): {e.reason}. ./gradlew bootRun 으로 서버를 먼저 띄울 것")
    return text if raw else (json.loads(text) if text else None)


def find_or_create_project(base, app, name):
    for p in request(base, "GET", "/api/projects"):
        if p.get("repoPath") and Path(p["repoPath"]).resolve() == app:
            return p["id"]
    return request(base, "POST", "/api/projects", {"name": name, "repoPath": str(app), "language": "java"})["id"]


def wait(base, job_id, timeout_minutes):
    deadline = time.time() + timeout_minutes * 60
    last = None
    while time.time() < deadline:
        job = request(base, "GET", f"/api/analyses/{job_id}")["job"]
        if job["status"] != last:
            print(f"  작업 #{job_id}: {job['status']} (토큰 {job['usedTokens']:,})", flush=True)
            last = job["status"]
        if job["status"] in FINISHED:
            return job
        if job["status"] == "WAITING_APPROVAL":
            raise SystemExit(f"작업 #{job_id} 가 승인을 기다림. 서버의 codeagent.agent.require-approval 을 끄거나 승인할 것")
        time.sleep(10)
    raise SystemExit(f"작업 #{job_id} 가 {timeout_minutes}분 안에 끝나지 않음")


def main(argv=None):
    parser = argparse.ArgumentParser(description="cotcha-net 로 벤치마크 앱을 여러 번 분석하고 채점한다")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--app", default=str(HERE / "shop-app"), help="분석할 벤치마크 앱 폴더")
    parser.add_argument("--expected", default=str(HERE / "shop-app.expected.json"))
    parser.add_argument("--runs", type=int, default=3, help="같은 앱을 몇 번 분석할지 (일관성을 재려면 2 이상)")
    parser.add_argument("--planner", choices=["llm", "paths"], default="llm")
    parser.add_argument("--max-intentions", type=int, default=25, help="할 일 상한 (벤치마크 위험 지점이 21개라 그 이상 권장)")
    parser.add_argument("--budget-tokens", type=int, default=3_000_000)
    parser.add_argument("--timeout-minutes", type=int, default=60)
    parser.add_argument("--out", help="결과 폴더 (기본 benchmark/results/<시각>)")
    args = parser.parse_args(argv)

    base = args.base_url.rstrip("/")
    app = Path(args.app).resolve()
    out = Path(args.out) if args.out else HERE / "results" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)

    project_id = find_or_create_project(base, app, "benchmark-" + app.name)
    graph = request(base, "POST", f"/api/projects/{project_id}/index")
    print(f"프로젝트 #{project_id} 코드 그래프: {json.dumps(graph, ensure_ascii=False)}")

    sarifs = []
    for i in range(1, args.runs + 1):
        print(f"[{i}/{args.runs}] 분석 시작 ({args.planner})", flush=True)
        job = request(base, "POST", f"/api/projects/{project_id}/analyses", {
            "planner": args.planner, "maxIntentions": args.max_intentions,
            "budgetTokens": args.budget_tokens, "requireApproval": False})
        job = wait(base, job["id"], args.timeout_minutes)
        (out / f"run-{i}.job.json").write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
        sarif = out / f"run-{i}.sarif"
        sarif.write_text(request(base, "GET", f"/api/analyses/{job['id']}/report.sarif", raw=True), encoding="utf-8")
        sarifs.append(str(sarif))
        if job["status"] == "FAILED":
            print(f"  작업 #{job['id']} 실패: {job.get('error')}", file=sys.stderr)

    print()
    return score.main([args.expected, *sarifs, "--json", str(out / "score.json")])


if __name__ == "__main__":
    sys.exit(main())
