package com.guildup.monitoring;

import com.guildup.monitoring.service.SafeMonitoringDataSanitizer;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeMonitoringDataSanitizerBudgetTests {
    private final SafeMonitoringDataSanitizer sanitizer = new SafeMonitoringDataSanitizer();

    @Test
    void oneBudgetBoundsAllBranchesAndRetainedCharacters() {
        Map<String, Object> branches = new LinkedHashMap<>();
        for (int branch = 0; branch < 32; branch++) {
            Map<String, Object> children = new LinkedHashMap<>();
            for (int child = 0; child < 32; child++) children.put("child-" + child, "value");
            branches.put("branch-" + branch, children);
        }
        Map<String, Object> result = sanitizer.sanitize(branches);
        assertThat(nodes(result)).isLessThanOrEqualTo(128);
        assertThat(nodes(result)).isLessThan(nodes(branches));

        Map<String, Object> largeValues = new LinkedHashMap<>();
        for (int i = 0; i < 32; i++) largeValues.put("key-" + i, "ordinary value ".repeat(2000));
        Map<String, Object> boundedText = sanitizer.sanitize(largeValues);
        assertThat(characters(boundedText)).isLessThanOrEqualTo(16_384);
        assertThat(characters(boundedText)).isGreaterThan(15_000);
    }

    @Test
    void aliasesAndCyclesAreNotCopiedRepeatedly() {
        Map<String, Object> source = new LinkedHashMap<>();
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("jobName", "aggregation");
        shared.put("backReference", source);
        source.put("first", shared);
        source.put("second", shared);
        source.put("self", source);

        Map<String, Object> result = sanitizer.sanitize(source);
        assertThat(result).containsEntry("second", "[ALIAS_OR_CYCLE]")
                .containsEntry("self", "[ALIAS_OR_CYCLE]");
        assertThat(((Map<?, ?>) result.get("first")).get("backReference")).isEqualTo("[ALIAS_OR_CYCLE]");
        assertThat(nodes(result)).isLessThan(10);
        assertThatThrownBy(() -> result.put("other", "value")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullIterableEntriesStillConsumeTheInspectionBudget() {
        AtomicInteger inspected = new AtomicInteger();
        Iterable<Object> nulls = () -> new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Object next() {
                if (inspected.incrementAndGet() > 128) throw new AssertionError("unbounded iteration");
                return null;
            }
        };

        Map<String, Object> result = sanitizer.sanitize(Map.of("items", nulls));
        assertThat(result).containsEntry("items", List.of());
        assertThat(inspected.get()).isBetween(1, 128);
    }

    @Test
    void skippedSensitiveOrNullMapEntriesStillConsumeTheInspectionBudget() {
        for (boolean sensitive : List.of(true, false)) {
            AtomicInteger inspected = new AtomicInteger();
            Map<String, Object> endless = new AbstractMap<>() {
                @Override public Set<Entry<String, Object>> entrySet() {
                    return new AbstractSet<>() {
                        @Override public int size() { return Integer.MAX_VALUE; }
                        @Override public Iterator<Entry<String, Object>> iterator() {
                            return new Iterator<>() {
                                @Override public boolean hasNext() { return true; }
                                @Override public Entry<String, Object> next() {
                                    int index = inspected.incrementAndGet();
                                    if (index > 128) throw new AssertionError("unbounded iteration");
                                    return new SimpleImmutableEntry<>(sensitive ? "secret-" + index : "item-" + index,
                                            sensitive ? "must not appear" : null);
                                }
                            };
                        }
                    };
                }
            };

            assertThat(sanitizer.sanitize(endless)).isEmpty();
            assertThat(inspected.get()).isEqualTo(128);
        }
    }

    @Test
    void boundedCharSequenceAndUnknownNumbersDoNotRenderTheirFullInput() {
        AtomicInteger copied = new AtomicInteger();
        CharSequence large = new CharSequence() {
            @Override public int length() { return 2_000_000; }
            @Override public char charAt(int index) { return 'x'; }
            @Override public CharSequence subSequence(int start, int end) {
                copied.set(end - start);
                return "x".repeat(end - start);
            }
            @Override public String toString() { throw new AssertionError("full input rendered"); }
        };

        Map<String, Object> result = sanitizer.sanitize(Map.of("text", large, "number", new ExplosiveNumber()));
        assertThat(copied.get()).isLessThanOrEqualTo(8192);
        assertThat((String) result.get("text")).hasSizeLessThanOrEqualTo(1000);
        assertThat(result).containsEntry("number", "ExplosiveNumber");
    }

    @Test
    void rejectsOverlongOrSensitiveKeysAndRedactsValuesWithoutChangingOrdinaryScalars() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ordinary".repeat(100) + "password", "never show this");
        source.put("accessToken", "never show this either");
        source.put("reason", "password=\"private value\" jobName=aggregation");
        source.put("attempt", 3);
        source.put("complete", false);

        Map<String, Object> result = sanitizer.sanitize(source);
        assertThat(result).containsEntry("attempt", 3).containsEntry("complete", false);
        assertThat(result).doesNotContainKey("accessToken");
        assertThat((String) result.get("reason")).doesNotContain("private value")
                .contains("[REDACTED]", "jobName=aggregation");
        assertThat(result).hasSize(3);
    }

    private static int nodes(Object value) {
        if (value instanceof Map<?, ?> map) return 1 + map.values().stream().mapToInt(SafeMonitoringDataSanitizerBudgetTests::nodes).sum();
        if (value instanceof List<?> list) return 1 + list.stream().mapToInt(SafeMonitoringDataSanitizerBudgetTests::nodes).sum();
        return 1;
    }

    private static int characters(Object value) {
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .mapToInt(entry -> entry.getKey().toString().length() + characters(entry.getValue())).sum();
        if (value instanceof List<?> list) return list.stream().mapToInt(SafeMonitoringDataSanitizerBudgetTests::characters).sum();
        return value.toString().length();
    }

    private static final class ExplosiveNumber extends Number {
        @Override public int intValue() { return 0; }
        @Override public long longValue() { return 0; }
        @Override public float floatValue() { return 0; }
        @Override public double doubleValue() { return 0; }
        @Override public String toString() { throw new AssertionError("arbitrary number rendered"); }
    }
}
