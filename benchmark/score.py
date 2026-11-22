#!/usr/bin/env python3
"""벤치마크 채점: 정답 파일과 분석 결과(SARIF)를 비교해 재현율·정밀도·함정 오탐·일관성을 잰다.

SARIF 는 be-code-agent 의 /api/analyses/{id}/report.sarif 나 mcp-code-agent 의 export_report(format=sarif) 결과다.
SARIF 를 여러 개 주면 같은 앱을 여러 번 분석한 것으로 보고, 실행마다 결과가 얼마나 같은지도 잰다.

    python benchmark/score.py benchmark/shop-app.expected.json run-1.sarif run-2.sarif run-3.sarif
    python benchmark/score.py benchmark/shop-app.expected.json results/*/run-*.sarif --json score.json

판정 규칙
- 발견의 위치(locations, relatedLocations) 중 하나가 정답 항목의 파일·줄 범위 안에 있으면 그 항목을 가리킨 것으로 본다.
- 취약점 항목을 가리키고 CWE 도 정답 목록에 있으면 맞힌 것(TP)이다. CWE 가 다르면 틀린 분류로 오탐에 센다.
- 함정(안전한 코드) 항목을 가리키면 함정 오탐이다. 아무 항목도 가리키지 않으면 기타 오탐이다.
- 기각(REJECTED)된 발견은 SARIF 에 없거나 여기서 뺀다. --confirmed-only 면 CONFIRMED 만 센다.
"""
import argparse
import itertools
import json
import re
import sys
from pathlib import Path

CWE_PATTERN = re.compile(r"CWE-\d+", re.IGNORECASE)


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def normalize_uri(uri):
    uri = uri.replace("\\", "/")
    uri = re.sub(r"^file:/+", "/", uri)
    return uri


def locations(result):
    for loc in list(result.get("locations") or []) + list(result.get("relatedLocations") or []):
        physical = loc.get("physicalLocation") or {}
        uri = (physical.get("artifactLocation") or {}).get("uri")
        line = (physical.get("region") or {}).get("startLine")
        if uri and isinstance(line, int):
            yield normalize_uri(uri), line


def cwe_of(result):
    for text in (result.get("ruleId") or "", json.dumps(result.get("properties") or {}, ensure_ascii=False)):
        m = CWE_PATTERN.search(text)
        if m:
            return m.group(0).upper()
    return None


def verdict_of(result):
    return ((result.get("properties") or {}).get("verdict") or "OPEN").upper()


def covers(item, uri, line):
    file = item["file"]
    if not (uri == file or uri.endswith("/" + file)):
        return False
    start, end = item["lines"]
    return start <= line <= end


def classify(result, vulns, traps):
    """발견 하나 → (종류, 항목 id). 종류는 tp, wrong_cwe, trap, other"""
    locs = list(locations(result))
    cwe = cwe_of(result)
    hit_vulns = [v for v in vulns if any(covers(v, u, l) for u, l in locs)]
    for v in hit_vulns:
        if cwe and cwe in [c.upper() for c in v["cwe"]]:
            return "tp", v["id"]
    hit_traps = [t for t in traps if any(covers(t, u, l) for u, l in locs)]
    if hit_traps:
        return "trap", hit_traps[0]["id"]
    if hit_vulns:
        return "wrong_cwe", hit_vulns[0]["id"]
    return "other", None


def score_run(sarif, expected, confirmed_only):
    vulns, traps = expected["vulnerabilities"], expected["safe"]
    findings = []
    for run in sarif.get("runs", []):
        for result in run.get("results", []):
            verdict = verdict_of(result)
            if verdict == "REJECTED" or (confirmed_only and verdict != "CONFIRMED"):
                continue
            kind, item = classify(result, vulns, traps)
            message = ((result.get("message") or {}).get("text") or "").split("\n")[0]
            findings.append({"kind": kind, "item": item, "cwe": cwe_of(result), "verdict": verdict, "title": message})

    found = sorted({f["item"] for f in findings if f["kind"] == "tp"})
    trapped = sorted({f["item"] for f in findings if f["kind"] == "trap"})
    tp_findings = sum(1 for f in findings if f["kind"] == "tp")
    return {
        "findings": len(findings),
        "found": found,
        "missed": [v["id"] for v in vulns if v["id"] not in found],
        "trapped": trapped,
        "wrongCwe": [f for f in findings if f["kind"] == "wrong_cwe"],
        "other": [f for f in findings if f["kind"] == "other"],
        "duplicates": tp_findings - len(found),
        "recall": len(found) / len(vulns) if vulns else 1.0,
        "precision": tp_findings / len(findings) if findings else None,
        "trapRate": len(trapped) / len(traps) if traps else 0.0,
    }


def jaccard(a, b):
    return 1.0 if not a and not b else len(a & b) / len(a | b)


def consistency(runs, expected):
    """실행마다 같은 결론을 냈는지. 항목마다 (찾았나/함정에 걸렸나)가 모든 실행에서 같으면 안정"""
    n = len(runs)
    items = {}
    for v in expected["vulnerabilities"]:
        items[v["id"]] = ("vulnerability", sum(v["id"] in r["found"] for r in runs))
    for t in expected["safe"]:
        items[t["id"]] = ("safe", sum(t["id"] in r["trapped"] for r in runs))
    stable = sum(1 for _, k in items.values() if k in (0, n))
    sets = [set(r["found"]) | {"trap:" + t for t in r["trapped"]} for r in runs]
    pairs = list(itertools.combinations(sets, 2))
    return {
        "runs": n,
        "stableItems": stable,
        "totalItems": len(items),
        "stableRate": stable / len(items) if items else 1.0,
        "meanJaccard": sum(jaccard(a, b) for a, b in pairs) / len(pairs) if pairs else 1.0,
        "perItem": {k: {"type": t, "hits": c} for k, (t, c) in items.items()},
    }


def pct(x):
    return "-" if x is None else f"{x * 100:.0f}%"


def stats(values):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    mean = sum(values) / len(values)
    return pct(mean) if len(values) == 1 else f"{pct(mean)} (최저 {pct(min(values))}, 최고 {pct(max(values))})"


def main(argv=None):
    parser = argparse.ArgumentParser(description="정답 파일과 SARIF 결과를 비교해 채점한다")
    parser.add_argument("expected", help="정답 파일 (예: benchmark/shop-app.expected.json)")
    parser.add_argument("sarif", nargs="+", help="분석 결과 SARIF 파일 (여러 개면 일관성도 잰다)")
    parser.add_argument("--confirmed-only", action="store_true", help="검증에서 CONFIRMED 된 발견만 센다")
    parser.add_argument("--json", help="채점 결과를 JSON 으로 저장할 경로")
    parser.add_argument("--min-recall", type=float, help="평균 재현율이 이 값(0~1)보다 낮으면 종료 코드 1")
    args = parser.parse_args(argv)

    expected = load_json(args.expected)
    runs = []
    for path in args.sarif:
        r = score_run(load_json(path), expected, args.confirmed_only)
        r["file"] = str(path)
        runs.append(r)

    vulns, traps = len(expected["vulnerabilities"]), len(expected["safe"])
    print(f"# {expected['name']} 채점 (취약점 {vulns}개, 함정 {traps}개, 실행 {len(runs)}번"
          f"{', CONFIRMED 만' if args.confirmed_only else ''})\n")
    for i, r in enumerate(runs, 1):
        print(f"## 실행 {i}: {Path(r['file']).name}")
        print(f"- 발견 {r['findings']}개 · 재현율 {pct(r['recall'])} ({len(r['found'])}/{vulns}) · 정밀도 {pct(r['precision'])}"
              f" · 함정 오탐 {len(r['trapped'])}/{traps}")
        if r["missed"]:
            print(f"- 놓친 취약점: {', '.join(r['missed'])}")
        if r["trapped"]:
            print(f"- 걸린 함정: {', '.join(r['trapped'])}")
        for f in r["wrongCwe"]:
            print(f"- 분류 틀림: {f['item']} 를 {f['cwe']} 로 보고 ({f['title']})")
        for f in r["other"]:
            print(f"- 정답에 없는 발견: {f['cwe']} {f['title']}")
        if r["duplicates"]:
            print(f"- 같은 취약점을 중복 보고: {r['duplicates']}개")
        print()

    precisions = [r["precision"] for r in runs if r["precision"] is not None]
    summary = {
        "recall": sum(r["recall"] for r in runs) / len(runs),
        "precision": sum(precisions) / len(precisions) if precisions else None,
        "trapRate": sum(r["trapRate"] for r in runs) / len(runs),
    }
    print("## 요약")
    print(f"- 재현율: {stats([r['recall'] for r in runs])}")
    print(f"- 정밀도: {stats([r['precision'] for r in runs])}")
    print(f"- 함정 오탐률: {stats([r['trapRate'] for r in runs])}")
    result = {"expected": args.expected, "confirmedOnly": args.confirmed_only, "summary": summary, "runs": runs}
    if len(runs) > 1:
        c = consistency(runs, expected)
        result["consistency"] = c
        print(f"- 일관성: 항목 {c['stableItems']}/{c['totalItems']}개가 모든 실행에서 같은 결론 ({pct(c['stableRate'])}),"
              f" 실행 간 평균 자카드 {c['meanJaccard']:.2f}")
        unstable = [f"{k}({v['hits']}/{c['runs']})" for k, v in c["perItem"].items() if v["hits"] not in (0, c["runs"])]
        if unstable:
            print(f"- 실행마다 달랐던 항목: {', '.join(unstable)}")

    if args.json:
        Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.min_recall is not None and summary["recall"] < args.min_recall:
        print(f"\n평균 재현율 {pct(summary['recall'])} 이 기준 {pct(args.min_recall)} 보다 낮음", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
