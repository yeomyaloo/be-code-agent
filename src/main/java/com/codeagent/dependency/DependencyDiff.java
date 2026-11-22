package com.codeagent.dependency;

import com.codeagent.dependency.DependencyService.DependencyReport;
import com.codeagent.dependency.DependencyService.DependencyView;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 지난 검사와 비교한 결과. 라이브러리는 group:artifact 로, 취약점은 (라이브러리, 취약점 id)로 맞춘다.
 *
 * @param changed                 버전이 바뀐 라이브러리
 * @param added                   새로 생긴 라이브러리 (group:artifact:version)
 * @param removed                 없어진 라이브러리
 * @param newVulnerabilities      지난번엔 없던 취약점 (라이브러리를 새로 넣었거나, 버전을 바꿨거나, 그사이 새로 공개됨)
 * @param resolvedVulnerabilities 지난번엔 있었는데 없어진 취약점 (버전을 올렸거나 라이브러리를 뺌)
 * @param vulnerabilitiesCompared 두 검사 모두 OSV 에 물어봤을 때만 취약점을 비교한다
 */
public record DependencyDiff(Long previousScanId, String previousCommitSha, OffsetDateTime previousScannedAt,
                             List<VersionChange> changed, List<String> added, List<String> removed,
                             List<VulnerabilityChange> newVulnerabilities, List<VulnerabilityChange> resolvedVulnerabilities,
                             boolean vulnerabilitiesCompared) {

    /** 버전이 바뀐 라이브러리. 같은 라이브러리가 여러 버전이면 쉼표로 잇는다 */
    public record VersionChange(String group, String artifact, String from, String to) {
    }

    public record VulnerabilityChange(String group, String artifact, String version, String id, String displayId,
                                      String severity, String summary, List<String> fixed) {
    }

    /** 달라진 것이 있는지 (JSON 속성으로 나가지 않도록 getter 이름을 피한다) */
    public boolean hasChanges() {
        return !(changed.isEmpty() && added.isEmpty() && removed.isEmpty()
                && newVulnerabilities.isEmpty() && resolvedVulnerabilities.isEmpty());
    }

    public static DependencyDiff between(DependencyScan previousScan, DependencyReport previous, DependencyReport current) {
        Map<String, Set<String>> before = versions(previous);
        Map<String, Set<String>> after = versions(current);

        List<VersionChange> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (var entry : after.entrySet()) {
            Set<String> old = before.get(entry.getKey());
            if (old == null) {
                entry.getValue().forEach(v -> added.add(entry.getKey() + ":" + v));
            } else if (!old.equals(entry.getValue())) {
                String[] ga = entry.getKey().split(":", 2);
                changed.add(new VersionChange(ga[0], ga[1], String.join(", ", old), String.join(", ", entry.getValue())));
            }
        }
        for (var entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                entry.getValue().forEach(v -> removed.add(entry.getKey() + ":" + v));
            }
        }

        boolean compared = previous.checked() && current.checked();
        List<VulnerabilityChange> newVulns = List.of();
        List<VulnerabilityChange> resolved = List.of();
        if (compared) {
            Map<String, VulnerabilityChange> beforeVulns = vulnerabilities(previous);
            Map<String, VulnerabilityChange> afterVulns = vulnerabilities(current);
            newVulns = afterVulns.entrySet().stream().filter(e -> !beforeVulns.containsKey(e.getKey())).map(Map.Entry::getValue)
                    .sorted(Comparator.comparingInt(v -> severityRank(v.severity()))).toList();
            resolved = beforeVulns.entrySet().stream().filter(e -> !afterVulns.containsKey(e.getKey())).map(Map.Entry::getValue)
                    .sorted(Comparator.comparingInt(v -> severityRank(v.severity()))).toList();
        }
        return new DependencyDiff(previousScan.getId(), previousScan.getCommitSha(), previousScan.getCreatedAt(),
                changed, added, removed, newVulns, resolved, compared);
    }

    private static Map<String, Set<String>> versions(DependencyReport report) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (DependencyView d : report.dependencies()) {
            result.computeIfAbsent(d.group() + ":" + d.artifact(), k -> new TreeSet<>()).add(d.version());
        }
        return result;
    }

    private static Map<String, VulnerabilityChange> vulnerabilities(DependencyReport report) {
        Map<String, VulnerabilityChange> result = new LinkedHashMap<>();
        for (DependencyView d : report.dependencies()) {
            for (Vulnerability v : d.vulnerabilities()) {
                result.putIfAbsent(d.group() + ":" + d.artifact() + "#" + v.id(), new VulnerabilityChange(
                        d.group(), d.artifact(), d.version(), v.id(), v.displayId(), v.severity(), v.summary(), v.fixed()));
            }
        }
        return result;
    }

    private static int severityRank(String severity) {
        int i = List.of("critical", "high", "medium", "low").indexOf(severity);
        return i < 0 ? 4 : i;
    }
}
