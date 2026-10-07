package com.guildup.monitoring.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/** Copies only diagnostic context; never propagates authentication or transactions. */
public final class LogContext {
    private LogContext() {}

    public static Scope scope(Map<String, ?> values) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        values.forEach(LogContext::put);
        return () -> restore(previous);
    }

    public static void put(String key, Object value) {
        if (value != null) MDC.put(key, String.valueOf(value));
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        ActivitySyncLog operation = ActivitySyncLog.current();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (var ignored = ActivitySyncLog.bind(operation)) { restore(captured); task.run(); }
            finally { restore(previous); }
        };
    }

    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        ActivitySyncLog operation = ActivitySyncLog.current();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (var ignored = ActivitySyncLog.bind(operation)) { restore(captured); return task.call(); }
            finally { restore(previous); }
        };
    }

    private static void restore(Map<String, String> values) {
        if (values == null || values.isEmpty()) MDC.clear();
        else MDC.setContextMap(values);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
