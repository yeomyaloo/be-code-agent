package com.codeagent.dependency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OSV(https://osv.dev) 공개 취약점 DB 에 Maven 라이브러리의 알려진 취약점을 묻는다.
 * 보내는 것은 라이브러리 좌표와 버전뿐이고, 소스코드는 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OsvClient {

    private static final int BATCH = 1000;

    private final DependencyProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** 취약점 내용은 거의 바뀌지 않으므로 서버가 떠 있는 동안 기억한다 */
    private final Map<String, JsonNode> details = new ConcurrentHashMap<>();

    /** 라이브러리마다 알려진 취약점. 취약점이 없으면 빈 목록 */
    public Map<Dependency, List<Vulnerability>> query(List<Dependency> dependencies) {
        Map<Dependency, List<String>> ids = new LinkedHashMap<>();
        for (int from = 0; from < dependencies.size(); from += BATCH) {
            List<Dependency> batch = dependencies.subList(from, Math.min(dependencies.size(), from + BATCH));
            ids.putAll(queryBatch(batch));
        }
        Set<String> unique = new LinkedHashSet<>();
        ids.values().forEach(unique::addAll);
        fetchDetails(unique);

        Map<Dependency, List<Vulnerability>> result = new LinkedHashMap<>();
        ids.forEach((dependency, vulnIds) -> result.put(dependency,
                vulnIds.stream().map(id -> toVulnerability(id, dependency)).toList()));
        return result;
    }

    private Map<Dependency, List<String>> queryBatch(List<Dependency> batch) {
        ObjectNode body = jsonMapper.createObjectNode();
        ArrayNode queries = body.putArray("queries");
        for (Dependency d : batch) {
            ObjectNode query = queries.addObject();
            query.putObject("package").put("ecosystem", "Maven").put("name", d.coordinate());
            query.put("version", d.version());
        }
        JsonNode response = send(HttpRequest.newBuilder(URI.create(properties.osvUrl() + "/v1/querybatch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))));
        JsonNode results = response.path("results");
        Map<Dependency, List<String>> ids = new LinkedHashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            List<String> vulnIds = new ArrayList<>();
            for (JsonNode vuln : results.path(i).path("vulns")) {
                vulnIds.add(vuln.path("id").asString());
            }
            ids.put(batch.get(i), vulnIds);
        }
        return ids;
    }

    private void fetchDetails(Set<String> ids) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (String id : ids) {
            if (details.containsKey(id)) {
                continue;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.osvUrl() + "/v1/vulns/" + id))
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds())).GET().build();
            pending.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() == 200) {
                            details.put(id, jsonMapper.readTree(r.body()));
                        }
                    })
                    // 내용을 못 받은 취약점은 id 만 보여준다
                    .exceptionally(e -> null));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
    }

    private Vulnerability toVulnerability(String id, Dependency dependency) {
        JsonNode node = details.get(id);
        String url = "https://osv.dev/vulnerability/" + id;
        if (node == null) {
            return new Vulnerability(id, List.of(), null, null, List.of(), List.of(), url);
        }
        List<String> aliases = new ArrayList<>();
        node.path("aliases").forEach(a -> aliases.add(a.asString()));
        List<String> cwes = new ArrayList<>();
        node.path("database_specific").path("cwe_ids").forEach(c -> cwes.add(c.asString()));
        Set<String> fixed = new LinkedHashSet<>();
        for (JsonNode affected : node.path("affected")) {
            if (!dependency.coordinate().equals(affected.path("package").path("name").asString())) {
                continue;
            }
            for (JsonNode range : affected.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    if (event.has("fixed")) {
                        fixed.add(event.path("fixed").asString());
                    }
                }
            }
        }
        String summary = node.path("summary").asString(null);
        if (summary == null || summary.isBlank()) {
            String detail = node.path("details").asString("");
            summary = detail.length() > 200 ? detail.substring(0, 200) + "…" : detail;
        }
        return new Vulnerability(id, aliases, summary, severity(node), List.copyOf(fixed), cwes, url);
    }

    /** GitHub 권고의 심각도(CRITICAL/HIGH/MODERATE/LOW)를 critical/high/medium/low 로 */
    private static String severity(JsonNode node) {
        String value = node.path("database_specific").path("severity").asString(null);
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "critical";
            case "HIGH" -> "high";
            case "MODERATE", "MEDIUM" -> "medium";
            case "LOW" -> "low";
            default -> null;
        };
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(properties.timeoutSeconds())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OsvException("OSV 가 오류를 돌려줌: HTTP " + response.statusCode());
            }
            return jsonMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OsvException("OSV 요청이 중단됨");
        } catch (java.io.IOException e) {
            throw new OsvException("OSV 에 연결하지 못함: " + e.getMessage());
        }
    }

    /** OSV 에 묻지 못함 */
    public static class OsvException extends RuntimeException {
        public OsvException(String message) {
            super(message);
        }
    }
}
