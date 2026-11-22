"""score.py 채점 규칙 테스트 (LLM 없이). python -m unittest discover -s benchmark"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import score

EXPECTED = Path(__file__).resolve().parent / "shop-app.expected.json"


def result(cwe, *locs, verdict="CONFIRMED", title="발견"):
    locations = [{"physicalLocation": {"artifactLocation": {"uri": uri}, "region": {"startLine": line}}} for uri, line in locs]
    r = {"ruleId": cwe, "message": {"text": title}, "locations": locations[:1], "properties": {"verdict": verdict}}
    if len(locations) > 1:
        r["relatedLocations"] = locations[1:]
    return r


def sarif(*results):
    return {"version": "2.1.0", "runs": [{"results": list(results)}]}


SQL = "src/main/java/com/example/shop/product/ProductQueryService.java"
ORDER = "src/main/java/com/example/shop/order/OrderController.java"


class ScoreTest(unittest.TestCase):

    def setUp(self):
        self.expected = score.load_json(EXPECTED)

    def test_위치와_CWE가_맞으면_맞힌_것이다(self):
        r = score.score_run(sarif(
            result("CWE-89", ("src/main/java/com/example/shop/product/ProductController.java", 23), (SQL, 22)),
            result("CWE-639", ("/home/ci/benchmark/shop-app/" + ORDER, 23)),
        ), self.expected, False)
        self.assertEqual(r["found"], ["idor-order", "sql-search"])
        self.assertEqual(r["precision"], 1.0)
        self.assertAlmostEqual(r["recall"], 2 / 11)

    def test_함정을_취약점이라_하면_함정_오탐이다(self):
        r = score.score_run(sarif(result("CWE-89", (SQL, 34)), result("CWE-639", (ORDER, 28))), self.expected, False)
        self.assertEqual(r["found"], [])
        self.assertEqual(r["trapped"], ["idor-receipt-checked", "sql-sort-allowlist"])
        self.assertEqual(r["precision"], 0.0)

    def test_CWE가_다르면_분류_틀림이고_정답에_없으면_기타_오탐이다(self):
        r = score.score_run(sarif(
            result("CWE-79", (SQL, 23)),
            result("CWE-200", ("src/main/java/com/example/shop/order/Order.java", 10)),
        ), self.expected, False)
        self.assertEqual([f["item"] for f in r["wrongCwe"]], ["sql-search"])
        self.assertEqual(len(r["other"]), 1)

    def test_기각된_발견은_빼고_CONFIRMED만_세기도_한다(self):
        s = sarif(result("CWE-89", (SQL, 23), verdict="REJECTED"), result("CWE-639", (ORDER, 23), verdict="UNCERTAIN"))
        self.assertEqual(score.score_run(s, self.expected, False)["found"], ["idor-order"])
        self.assertEqual(score.score_run(s, self.expected, True)["findings"], 0)

    def test_같은_취약점을_두번_보고하면_중복으로_센다(self):
        r = score.score_run(sarif(result("CWE-89", (SQL, 23)), result("CWE-89", (SQL, 22))), self.expected, False)
        self.assertEqual(r["duplicates"], 1)
        self.assertEqual(r["precision"], 1.0)

    def test_실행마다_결과가_다르면_일관성이_떨어진다(self):
        a = sarif(result("CWE-89", (SQL, 23)), result("CWE-639", (ORDER, 23)))
        b = sarif(result("CWE-89", (SQL, 23)), result("CWE-89", (SQL, 34)))
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for i, s in enumerate([a, a, b]):
                p = Path(d) / f"run-{i}.sarif"
                p.write_text(json.dumps(s), encoding="utf-8")
                paths.append(str(p))
            out = Path(d) / "score.json"
            with redirect_stdout(io.StringIO()) as text:
                code = score.main([str(EXPECTED), *paths, "--json", str(out), "--min-recall", "0.5"])
            report = json.loads(out.read_text(encoding="utf-8"))

        self.assertEqual(code, 1)
        c = report["consistency"]
        self.assertEqual(c["perItem"]["sql-search"]["hits"], 3)
        self.assertEqual(c["perItem"]["idor-order"]["hits"], 2)
        self.assertEqual(c["perItem"]["sql-sort-allowlist"]["hits"], 1)
        self.assertEqual(c["stableItems"], c["totalItems"] - 2)
        self.assertIn("실행마다 달랐던 항목: idor-order(2/3), sql-sort-allowlist(1/3)", text.getvalue())


if __name__ == "__main__":
    unittest.main()
