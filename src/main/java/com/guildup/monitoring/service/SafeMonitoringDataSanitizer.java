package com.guildup.monitoring.service;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class SafeMonitoringDataSanitizer {
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|authorization|session[-_]?cookie|set[-_]?cookie|email[-_]?verification).*"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/]+=*");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|authorization)\\s*[=:]\\s*[^,\\s]+"
    );
    private static final int MAX_STRING_LENGTH = 1_000;

    public String sanitizeText(String value) {
        if (value == null) return "";
        String sanitized = KEY_VALUE_SECRET.matcher(BEARER.matcher(value).replaceAll("Bearer [REDACTED]"))
                .replaceAll("$1=[REDACTED]");
        return sanitized.substring(0, Math.min(MAX_STRING_LENGTH, sanitized.length()));
    }

    public Map<String, Object> sanitize(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || SENSITIVE_KEY.matcher(key).matches() || value == null) return;
            result.put(key, sanitizeValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof CharSequence sequence) return sanitizeText(sequence.toString());
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> converted = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> {
                String stringKey = Objects.toString(key, "");
                if (!stringKey.isBlank() && !SENSITIVE_KEY.matcher(stringKey).matches() && nestedValue != null)
                    converted.put(stringKey, sanitizeValue(nestedValue));
            });
            return converted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(item -> { if (item != null) values.add(sanitizeValue(item)); });
            return values;
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                Object item = Array.get(value, i);
                if (item != null) values.add(sanitizeValue(item));
            }
            return values;
        }
        return sanitizeText(value.getClass().getSimpleName());
    }
}
