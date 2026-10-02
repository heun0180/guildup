package com.guildup.monitoring.service;

import com.guildup.monitoring.logging.SafeLogText;
import org.springframework.stereotype.Component;
import java.lang.reflect.Array;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class SafeMonitoringDataSanitizer {
    private static final int MAX_NODES = 128;
    private static final int MAX_INSPECTIONS = 128;
    private static final int MAX_CHARACTERS = 16_384;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_CONTAINER_ITEMS = 32;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|api[-_]?key|token|authorization|cookie|session[-_]?id|email[-_]?verification).*"
    );

    public String sanitizeText(String value) { return SafeLogText.limited(value, 1000); }
    public Map<String, Object> sanitize(Map<String, ?> metadata) {
        if (metadata == null) return Map.of();
        Budget budget = new Budget();
        budget.nodes--;
        budget.visited.add(metadata);
        return map(metadata, 0, budget);
    }

    private Map<String, Object> map(Map<?, ?> metadata, int depth, Budget budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<? extends Map.Entry<?, ?>> entries = metadata.entrySet().iterator();
        while (result.size() < MAX_CONTAINER_ITEMS && budget.canInspect() && entries.hasNext()) {
            budget.inspections--;
            var entry = entries.next();
            // Metadata keys are strings. Never invoke an arbitrary object's toString here.
            if (!(entry.getKey() instanceof CharSequence keySequence) || keySequence.length() > 512) continue;
            String key = keySequence.toString();
            Object item = entry.getValue();
            if (key.isBlank() || SENSITIVE_KEY.matcher(key).matches() || item == null) continue;
            String safeKey = budget.text(key, 100);
            if (safeKey.isEmpty()) break;
            Object safeValue = value(item, depth + 1, budget);
            if (safeValue != null) result.put(safeKey, safeValue);
        }
        return Collections.unmodifiableMap(result);
    }

    private Object value(Object value, int depth, Budget budget) {
        if (budget.nodes == 0 || budget.characters == 0) return null;
        budget.nodes--;
        if (depth >= MAX_DEPTH) return budget.text("[DEPTH_LIMIT]", 1000);
        if (value instanceof CharSequence sequence) {
            String prefix = sequence instanceof String string ? string
                    : sequence.subSequence(0, Math.min(sequence.length(), 8192)).toString();
            return budget.text(prefix, 1000);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof Boolean) {
            // Other Number implementations may retain or render arbitrarily large data.
            return budget.reserveCharacters(value.toString().length()) ? value : null;
        }
        if (value instanceof Enum<?> enumeration) return budget.text(enumeration.name(), 1000);
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            if (!budget.visited.add(value)) return budget.text("[ALIAS_OR_CYCLE]", 1000);
        }
        if (value instanceof Map<?, ?> nested) return map(nested, depth, budget);
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            Iterator<?> items = iterable.iterator();
            while (values.size() < MAX_CONTAINER_ITEMS && budget.canInspect() && items.hasNext()) {
                budget.inspections--;
                Object item = items.next();
                if (item != null) {
                    Object safeValue = value(item, depth + 1, budget);
                    if (safeValue != null) values.add(safeValue);
                }
            }
            return List.copyOf(values);
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_CONTAINER_ITEMS, Array.getLength(value)) && budget.canInspect(); i++) {
                budget.inspections--;
                Object item = Array.get(value, i);
                if (item != null) {
                    Object safeValue = value(item, depth + 1, budget);
                    if (safeValue != null) values.add(safeValue);
                }
            }
            return List.copyOf(values);
        }
        return budget.text(value.getClass().getSimpleName(), 1000);
    }

    /** One budget for the entire object graph, including skipped/null entries. */
    private static final class Budget {
        private int nodes = MAX_NODES;
        private int inspections = MAX_INSPECTIONS;
        private int characters = MAX_CHARACTERS;
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        private boolean canInspect() { return nodes > 0 && inspections > 0 && characters > 0; }

        private boolean reserveCharacters(int count) {
            if (count > characters) {
                characters = 0;
                return false;
            }
            characters -= count;
            return true;
        }

        private String text(String text, int fieldLimit) {
            int limit = Math.min(fieldLimit, characters);
            String safe = SafeLogText.limited(text, limit);
            // The truncation marker also counts against the retained-character budget.
            if (safe.length() > limit) safe = safe.substring(0, limit);
            characters -= safe.length();
            return safe;
        }
    }
}
