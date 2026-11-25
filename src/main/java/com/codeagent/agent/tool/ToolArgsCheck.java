package com.codeagent.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 도구를 부르기 전에 인자를 입력 스키마와 맞춰 본다. 모르는 인자(오타), 빠진 필수 인자, 허용하지 않는 값, 배열 안 객체의 필드를
 * 한 번에 알려서 Claude 가 한 번에 고쳐 다시 부르게 한다. 모르는 인자를 조용히 무시하면 엉뚱한 결과(빈 검색 결과 등)를 믿게 된다.
 */
public final class ToolArgsCheck {

    private ToolArgsCheck() {
    }

    /** 도구의 입력 스키마(properties, required)로 맞춰 본다. 문제가 있으면 ToolInputException */
    public static void validate(AgentTool tool, Map<String, Object> values) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("properties", tool.properties());
        schema.put("required", tool.required());
        validate(tool.name(), schema, values);
    }

    /** 문제가 있으면 ToolInputException (고칠 점 목록 + 이 도구의 인자 안내) */
    static void validate(String tool, Map<String, Object> schema, Map<String, Object> values) {
        List<String> problems = new ArrayList<>();
        check("", schema, values, problems);
        if (problems.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(tool).append(" 의 인자를 고쳐서 다시 부를 것:\n");
        problems.forEach(p -> sb.append("- ").append(p).append('\n'));
        sb.append(describe(schema));
        throw new ToolInputException(sb.toString().strip());
    }

    @SuppressWarnings("unchecked")
    private static void check(String prefix, Map<String, Object> schema, Map<String, Object> values, List<String> problems) {
        Map<String, Map<String, Object>> properties = properties(schema);
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        for (String key : values.keySet()) {
            if (!properties.containsKey(key)) {
                String guess = closest(key, properties.keySet());
                problems.add(prefix + key + ": 이 도구의 인자가 아님" + (guess == null ? "" : ". " + prefix + guess + " 을(를) 말한 건가요?"));
            }
        }
        for (String name : required) {
            Object value = values.get(name);
            if (value == null || value instanceof String s && s.isBlank()
                    || value instanceof List<?> list && list.isEmpty() && "array".equals(properties.get(name).get("type"))) {
                problems.add(prefix + name + ": 꼭 넣어야 함 (" + description(properties.get(name)) + ")");
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Map<String, Object> property = properties.get(entry.getKey());
            Object value = entry.getValue();
            if (property == null || value == null) {
                continue;
            }
            String path = prefix + entry.getKey();
            Object allowed = property.get("enum");
            if (allowed instanceof List<?> options && !options.isEmpty()
                    && options.stream().noneMatch(o -> o.toString().equalsIgnoreCase(value.toString().strip()))) {
                problems.add(path + ": " + options + " 중 하나여야 함 (받은 값: " + value + ")");
            }
            if ("integer".equals(property.get("type")) && !(value instanceof Number) && !value.toString().strip().matches("-?\\d+")) {
                problems.add(path + ": 정수여야 함 (받은 값: " + value + ")");
            }
            if ("array".equals(property.get("type")) && property.get("items") instanceof Map<?, ?> items
                    && "object".equals(items.get("type"))) {
                if (!(value instanceof List<?> list)) {
                    problems.add(path + ": 객체 배열이어야 함. 예: [" + example((Map<String, Object>) items) + "]");
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof Map<?, ?> item) {
                        check(path + "[" + i + "].", (Map<String, Object>) items, (Map<String, Object>) item, problems);
                    } else {
                        problems.add(path + "[" + i + "]: 객체여야 함. 예: " + example((Map<String, Object>) items));
                    }
                }
            }
        }
    }

    /** 이 도구의 인자 안내 (필수 표시, 설명, 배열 항목 예시) */
    @SuppressWarnings("unchecked")
    static String describe(Map<String, Object> schema) {
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        return "이 도구의 인자:\n" + properties(schema).entrySet().stream().map(e -> {
            String line = "  " + e.getKey() + (required.contains(e.getKey()) ? " (필수)" : "") + ": " + description(e.getValue());
            if (e.getValue().get("items") instanceof Map<?, ?> items && "object".equals(items.get("type"))) {
                line += "\n    항목 예: " + example((Map<String, Object>) items);
            }
            return line;
        }).collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> properties(Map<String, Object> schema) {
        return (Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of());
    }

    private static String description(Map<String, Object> property) {
        Object description = property.get("description");
        Object options = property.get("enum");
        String text = description == null ? property.getOrDefault("type", "").toString() : description.toString();
        return options instanceof List<?> list && !text.contains(list.get(0).toString()) ? text + " " + list : text;
    }

    /** {"file": "…", "line": 1} 꼴의 예시 */
    @SuppressWarnings("unchecked")
    private static String example(Map<String, Object> itemSchema) {
        return properties(itemSchema).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + ("integer".equals(e.getValue().get("type")) ? "1" : "\"…\""))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /** 오타·다른 이름으로 보이는 가장 가까운 인자 (reason → reasoning, filePattern → file_glob). 없으면 null */
    static String closest(String key, Iterable<String> names) {
        String normalized = normalize(key);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String name : names) {
            String candidate = normalize(name);
            int score;
            if (candidate.equals(normalized)) {
                score = 0;
            } else if (candidate.startsWith(normalized) || normalized.startsWith(candidate)) {
                score = 1;
            } else if (candidate.contains(normalized) || normalized.contains(candidate)) {
                score = 2;
            } else {
                int distance = distance(normalized, candidate);
                score = distance <= Math.max(2, normalized.length() / 3) ? 2 + distance : Integer.MAX_VALUE;
            }
            if (score < bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s]", "");
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
