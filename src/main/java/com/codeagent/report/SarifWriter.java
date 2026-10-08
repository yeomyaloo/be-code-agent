package com.codeagent.report;

import com.codeagent.report.ReportService.Evidence;
import com.codeagent.report.ReportService.Report;
import com.codeagent.report.ReportService.ReportFinding;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SARIF 2.1.0 보고서. GitHub 코드 스캐닝 등 정적 분석 도구들이 읽는 표준 형식이다.
 */
public final class SarifWriter {

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String INFORMATION_URI = "https://github.com/yeomyaloo/be-code-agent";

    private SarifWriter() {
    }

    public static String write(Report report, JsonMapper jsonMapper) {
        List<String> ruleIds = report.findings().stream().map(f -> Cwe.id(f.cwe())).distinct().toList();

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "be-code-agent");
        driver.put("informationUri", INFORMATION_URI);
        driver.put("rules", ruleIds.stream().map(SarifWriter::rule).toList());

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        if (report.gitUrl() != null) {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("repositoryUri", report.gitUrl());
            provenance.put("revisionId", report.commitSha());
            provenance.put("branch", report.gitBranch());
            run.put("versionControlProvenance", List.of(provenance));
        }
        run.put("results", report.findings().stream().map(f -> result(f, ruleIds.indexOf(Cwe.id(f.cwe())))).toList());
        run.put("properties", Map.of("jobId", report.jobId(), "jobStatus", report.jobStatus(),
                "dismissedCount", report.dismissedCount()));

        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", SCHEMA);
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sarif);
    }

    private static Map<String, Object> rule(String ruleId) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId);
        rule.put("name", ruleId);
        rule.put("shortDescription", Map.of("text", Cwe.name(ruleId)));
        if (Cwe.url(ruleId) != null) {
            rule.put("helpUri", Cwe.url(ruleId));
        }
        List<String> tags = new ArrayList<>(List.of("security"));
        if (ruleId.startsWith("CWE-")) {
            tags.add("external/cwe/" + ruleId.toLowerCase());
        }
        rule.put("properties", Map.of("tags", tags));
        return rule;
    }

    private static Map<String, Object> result(ReportFinding finding, int ruleIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", Cwe.id(finding.cwe()));
        result.put("ruleIndex", ruleIndex);
        result.put("level", level(finding.severity()));
        result.put("message", Map.of("text", finding.description() == null || finding.description().isBlank()
                ? finding.title() : finding.title() + "\n\n" + finding.description()));

        List<Evidence> evidence = finding.evidence();
        if (!evidence.isEmpty()) {
            result.put("locations", List.of(location(evidence.getFirst(), null)));
            if (evidence.size() > 1) {
                List<Map<String, Object>> related = new ArrayList<>();
                for (int i = 1; i < evidence.size(); i++) {
                    related.add(location(evidence.get(i), i));
                }
                result.put("relatedLocations", related);
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("findingId", finding.id());
        properties.put("severity", finding.severity());
        properties.put("security-severity", securitySeverity(finding.severity()));
        properties.put("confidence", finding.confidence());
        properties.put("verdict", finding.status());
        properties.put("verdictReasoning", finding.verdictReasoning());
        properties.put("entryPoint", finding.entryPoint());
        properties.put("sink", finding.sink());
        properties.put("reviewDecision", finding.reviewDecision());
        result.put("properties", properties);
        return result;
    }

    private static Map<String, Object> location(Evidence evidence, Integer id) {
        Map<String, Object> physical = new LinkedHashMap<>();
        physical.put("artifactLocation", Map.of("uri", evidence.file()));
        physical.put("region", Map.of("startLine", evidence.line()));
        Map<String, Object> location = new LinkedHashMap<>();
        if (id != null) {
            location.put("id", id);
        }
        location.put("physicalLocation", physical);
        if (evidence.note() != null) {
            location.put("message", Map.of("text", evidence.note()));
        }
        return location;
    }

    static String level(String severity) {
        return switch (severity) {
            case "critical", "high" -> "error";
            case "medium" -> "warning";
            default -> "note";
        };
    }

    /** GitHub 코드 스캐닝이 심각도 표시에 쓰는 0.0~10.0 점수 */
    static String securitySeverity(String severity) {
        return switch (severity) {
            case "critical" -> "9.5";
            case "high" -> "8.0";
            case "medium" -> "5.5";
            case "low" -> "3.0";
            default -> "0.0";
        };
    }
}
