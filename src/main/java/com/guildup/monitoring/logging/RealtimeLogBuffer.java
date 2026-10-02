package com.guildup.monitoring.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class RealtimeLogBuffer {
    private static final Set<String> CONTEXT_KEYS = Set.of("requestId", "userId", "communityId", "communityMemberId",
            "bingoEventId", "bingoId", "killCompetitionId", "participantId", "matchId", "pubgPlayerId",
            "discordGuildId", "discordUserId", "jobName", "stage", "endpoint", "method", "shard");
    private final LiveLogEntry[] entries;
    private final String instance = UUID.randomUUID().toString();
    private long sequence;
    private int size;

    public RealtimeLogBuffer(@Value("${monitoring.logs.capacity:1000}") int capacity) {
        entries = new LiveLogEntry[Math.max(500, Math.min(2000, capacity))];
    }

    public void append(ILoggingEvent event) {
        if (event.getLevel().toInt() < Level.INFO_INT) return;
        // Work outside the buffer lock; no network, database, or event fan-out here.
        Map<String, String> context = new LinkedHashMap<>();
        int inspected = 0;
        for (var entry : event.getMDCPropertyMap().entrySet()) {
            if (inspected++ >= 64) break;
            if (CONTEXT_KEYS.contains(entry.getKey())) context.put(entry.getKey(), SafeLogText.limited(entry.getValue(), 300));
        }
        String message = SafeLogText.limited(event.getFormattedMessage(), 2000);
        String trace = trace(event.getThrowableProxy());
        synchronized (this) {
            long next = ++sequence;
            entries[(int) ((next - 1) % entries.length)] = new LiveLogEntry(instance + ":" + next,
                    Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                    SafeLogText.limited(event.getLoggerName(), 200), SafeLogText.limited(event.getThreadName(), 120),
                    message, trace, Collections.unmodifiableMap(context));
            size = Math.min(size + 1, entries.length);
        }
    }

    private String trace(IThrowableProxy failure) {
        if (failure == null) return "";
        StringBuilder result = new StringBuilder();
        Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IThrowableProxy current = failure; current != null && visited.add(current) && result.length() < 6000;
             current = current.getCause()) {
            if (!result.isEmpty()) result.append("Caused by: ");
            result.append(SafeLogText.limited(current.getClassName(), 200)).append(": ")
                    .append(SafeLogText.limited(current.getMessage(), 500)).append('\n');
            var frames = current.getStackTraceElementProxyArray();
            if (frames != null) for (var frame : frames) {
                if (result.length() >= 6000) break;
                result.append("\tat ").append(SafeLogText.limited(frame.getStackTraceElement().toString(), 600)).append('\n');
            }
        }
        return SafeLogText.limited(result.toString(), 6000);
    }

    /** A bounded copy. A stale cursor reports a gap rather than allocating an unbounded replay. */
    public synchronized Batch read(String lastId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        long earliest = sequence - size + 1;
        long cursor = cursor(lastId);
        boolean gap = lastId != null && (cursor < earliest - 1 || cursor > sequence);
        long start = lastId == null || cursor < 0 || cursor > sequence
                ? Math.max(earliest, sequence - safeLimit + 1) : Math.max(earliest, cursor + 1);
        List<LiveLogEntry> batch = new ArrayList<>();
        for (long i = start; i <= sequence && batch.size() < safeLimit; i++)
            batch.add(entries[(int) ((i - 1) % entries.length)]);
        return new Batch(List.copyOf(batch), gap);
    }

    private long cursor(String id) {
        if (id == null || !id.startsWith(instance + ":") || id.length() > 80) return -1;
        try { return Long.parseLong(id.substring(instance.length() + 1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    public int capacity() { return entries.length; }
    public synchronized int size() { return size; }
    public record Batch(List<LiveLogEntry> entries, boolean gap) {}
}
