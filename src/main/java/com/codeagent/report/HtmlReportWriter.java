package com.codeagent.report;

import com.codeagent.report.ReportService.Evidence;
import com.codeagent.report.ReportService.Report;
import com.codeagent.report.ReportService.ReportFinding;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 한 파일로 열어볼 수 있는 HTML 보고서. 스크립트는 넣지 않고, 발견 내용(LLM이 쓴 글, 코드)은 모두 이스케이프한다.
 */
public final class HtmlReportWriter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "CONFIRMED", "확정", "OPEN", "검증 대기", "UNCERTAIN", "판단 불가", "REJECTED", "오탐");
    private static final Map<String, String> REVIEW_LABELS = Map.of(
            "ACCEPTED", "인정", "FALSE_POSITIVE", "오탐", "FIXED", "조치 완료", "WONT_FIX", "위험 수용");

    private static final String STYLE = """
            :root { --fg:#1f2328; --muted:#57606a; --line:#d0d7de; --bg:#ffffff; --soft:#f6f8fa;
                    --critical:#8b0000; --high:#cf222e; --medium:#bf8700; --low:#0969da; --info:#57606a; }
            * { box-sizing: border-box; }
            body { margin:0; padding:32px 16px; background:var(--bg); color:var(--fg);
                   font:15px/1.6 Pretendard,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR','Segoe UI',sans-serif; }
            main { max-width:960px; margin:0 auto; }
            h1 { margin:0 0 4px; font-size:26px; } h2 { margin:40px 0 12px; font-size:20px; }
            .muted { color:var(--muted); } code, pre { font-family:Consolas,'JetBrains Mono',monospace; font-size:13px; }
            .meta { display:grid; grid-template-columns:max-content 1fr; gap:4px 16px; margin:16px 0; }
            .cards { display:flex; flex-wrap:wrap; gap:8px; margin:16px 0; }
            .card { border:1px solid var(--line); border-radius:8px; padding:10px 14px; min-width:110px; background:var(--soft); }
            .card b { display:block; font-size:22px; }
            table { width:100%; border-collapse:collapse; } th, td { text-align:left; padding:8px; border-bottom:1px solid var(--line); vertical-align:top; }
            .sev { display:inline-block; padding:1px 8px; border-radius:10px; color:#fff; font-size:12px; font-weight:700; }
            .sev-critical { background:var(--critical); } .sev-high { background:var(--high); } .sev-medium { background:var(--medium); }
            .sev-low { background:var(--low); } .sev-info { background:var(--info); }
            .finding { border:1px solid var(--line); border-radius:10px; padding:16px 20px; margin:16px 0; }
            .finding h3 { margin:0 0 8px; font-size:17px; }
            .finding dl { display:grid; grid-template-columns:max-content 1fr; gap:6px 16px; margin:12px 0 0; }
            .finding dt { color:var(--muted); } .finding dd { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; }
            ul { margin:0; padding-left:18px; }
            """;

    private HtmlReportWriter() {
    }

    public static String write(Report report) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"ko\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(e(report.projectName())).append(" 보안 분석 보고서</title>\n")
                .append("<style>").append(STYLE).append("</style>\n</head>\n<body>\n<main>\n");

        html.append("<h1>").append(e(report.projectName())).append(" 보안 분석 보고서</h1>\n")
                .append("<div class=\"muted\">be-code-agent · 분석 작업 #").append(report.jobId()).append("</div>\n");

        html.append("<div class=\"meta\">\n");
        if (report.gitUrl() != null) {
            meta(html, "저장소", report.gitUrl());
            meta(html, "브랜치 / 커밋", report.gitBranch() + " @ " + report.commitSha());
        }
        meta(html, "작업 상태", report.jobStatus());
        meta(html, "분석 시간", time(report.startedAt()) + " ~ " + time(report.finishedAt()));
        meta(html, "사용 토큰", String.format("%,d", report.usedTokens()));
        meta(html, "보고서 생성", time(report.generatedAt()));
        html.append("</div>\n");

        html.append("<div class=\"cards\">\n");
        for (String severity : ReportService.SEVERITIES) {
            long count = report.findings().stream().filter(f -> f.severity().equals(severity)).count();
            html.append("<div class=\"card\"><span class=\"sev sev-").append(severity).append("\">")
                    .append(severity.toUpperCase()).append("</span><b>").append(count).append("</b></div>\n");
        }
        boolean dismissedIncluded = report.findings().stream().anyMatch(ReportFinding::dismissed);
        html.append("<div class=\"card\"><span class=\"muted\">").append(dismissedIncluded ? "오탐 (목록에 포함)" : "오탐 (목록에서 제외)").append("</span><b>")
                .append(report.dismissedCount()).append("</b></div>\n</div>\n");

        if (report.findings().isEmpty()) {
            html.append("<p>보고할 발견이 없습니다.</p>\n");
        } else {
            summaryTable(html, report);
            html.append("<h2>상세</h2>\n");
            report.findings().forEach(f -> detail(html, f));
        }

        html.append("<p class=\"muted\">이 보고서는 정적 분석과 LLM 판단에 기반하므로 오탐·미탐이 있을 수 있습니다. 최종 판단은 사람이 검토해야 합니다.</p>\n")
                .append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void summaryTable(StringBuilder html, Report report) {
        html.append("<h2>요약</h2>\n<table>\n<thead><tr><th>#</th><th>심각도</th><th>취약점</th><th>CWE</th><th>판정</th><th>검토</th></tr></thead>\n<tbody>\n");
        for (ReportFinding f : report.findings()) {
            html.append("<tr><td><a href=\"#finding-").append(f.id()).append("\">").append(f.id()).append("</a></td>")
                    .append("<td>").append(severityBadge(f.severity())).append("</td>")
                    .append("<td>").append(e(f.title())).append("</td>")
                    .append("<td>").append(e(Cwe.id(f.cwe()))).append("</td>")
                    .append("<td>").append(e(STATUS_LABELS.getOrDefault(f.status(), f.status()))).append("</td>")
                    .append("<td>").append(e(review(f))).append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static void detail(StringBuilder html, ReportFinding f) {
        html.append("<section class=\"finding\" id=\"finding-").append(f.id()).append("\">\n")
                .append("<h3>").append(severityBadge(f.severity())).append(' ').append(e(f.title())).append("</h3>\n")
                .append("<div class=\"muted\">#").append(f.id()).append(" · ").append(e(Cwe.id(f.cwe()))).append(' ')
                .append(e(Cwe.name(f.cwe()))).append(" · 판정: ").append(e(STATUS_LABELS.getOrDefault(f.status(), f.status())))
                .append("</div>\n<dl>\n");
        item(html, "진입점", f.entryPoint());
        item(html, "위험 지점", f.sink());
        item(html, "설명", f.description());
        item(html, "공격 예시", f.exploitScenario());
        if (!f.evidence().isEmpty()) {
            html.append("<dt>근거 코드</dt><dd><ul>");
            for (Evidence ev : f.evidence()) {
                html.append("<li><code>").append(e(ev.file())).append(':').append(ev.line()).append("</code>");
                if (ev.note() != null) {
                    html.append(" ").append(e(ev.note()));
                }
                html.append("</li>");
            }
            html.append("</ul></dd>\n");
        }
        item(html, "검증 근거", f.verdictReasoning());
        item(html, "확인한 방어 장치", f.blockingControls());
        item(html, "확신도", f.confidence());
        if (f.reviewDecision() != null) {
            item(html, "사람 검토", review(f) + (f.reviewComment() == null ? "" : " - " + f.reviewComment()));
        }
        html.append("</dl>\n</section>\n");
    }

    private static String review(ReportFinding f) {
        return f.reviewDecision() == null ? "-" : REVIEW_LABELS.getOrDefault(f.reviewDecision(), f.reviewDecision());
    }

    private static String severityBadge(String severity) {
        return "<span class=\"sev sev-" + e(severity) + "\">" + e(severity.toUpperCase()) + "</span>";
    }

    private static void meta(StringBuilder html, String label, String value) {
        html.append("<span class=\"muted\">").append(e(label)).append("</span><span>").append(e(value)).append("</span>\n");
    }

    private static void item(StringBuilder html, String label, String value) {
        if (value != null && !value.isBlank()) {
            html.append("<dt>").append(e(label)).append("</dt><dd>").append(e(value)).append("</dd>\n");
        }
    }

    private static String time(OffsetDateTime time) {
        return time == null ? "-" : time.format(TIME);
    }

    /** HTML 이스케이프 */
    static String e(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
