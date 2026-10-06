package com.codeagent.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Claude가 보낸 도구 입력. 타입을 확인하면서 꺼내고, 잘못되면 {@link ToolInputException}을 던진다.
 */
public record ToolInput(Map<String, Object> values) {

    public String string(String name) {
        return optionalString(name).orElseThrow(() -> new ToolInputException("필수 입력 '" + name + "'이 없음"));
    }

    public Optional<String> optionalString(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String s)) {
            throw new ToolInputException("'" + name + "'은 문자열이어야 함");
        }
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    public String oneOf(String name, Set<String> allowed) {
        String value = string(name);
        if (!allowed.contains(value)) {
            throw new ToolInputException("'" + name + "'은 " + allowed + " 중 하나여야 함: " + value);
        }
        return value;
    }

    public Optional<Long> optionalLong(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (value instanceof String s && s.matches("\\d+")) {
            return Optional.of(Long.parseLong(s));
        }
        throw new ToolInputException("'" + name + "'은 정수여야 함");
    }

    public List<Long> longList(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ToolInputException("'" + name + "'은 정수 배열이어야 함");
        }
        return list.stream().map(v -> {
            if (v instanceof Number n) {
                return n.longValue();
            }
            throw new ToolInputException("'" + name + "'은 정수 배열이어야 함");
        }).toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> objectList(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(v -> v instanceof Map)) {
            throw new ToolInputException("'" + name + "'은 객체 배열이어야 함");
        }
        return (List<Map<String, Object>>) list;
    }
}
