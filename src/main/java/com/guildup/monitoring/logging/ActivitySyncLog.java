package com.guildup.monitoring.logging;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.pubg.exception.PubgApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Per-operation diagnostics only. Reuses the file log, MDC and existing monitoring writer. */
public final class ActivitySyncLog {
    private static final Logger log = LoggerFactory.getLogger(ActivitySyncLog.class);
    private static final ThreadLocal<ActivitySyncLog> CURRENT = new ThreadLocal<>();
    private final MonitoringEventService monitoring;
    private final Long communityId, communityGameId, userId;
    private final String syncId;
    private final Instant startedAt;
    private final Clock clock;
    private final long startedNanos = System.nanoTime();
    private final Map<String, Object> details = new LinkedHashMap<>();
    private final AtomicLong playerApiCalls = new AtomicLong(), matchApiCalls = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong(), sharedFetches = new AtomicLong();
    private final AtomicLong successfulMatches = new AtomicLong(), missingMatches = new AtomicLong(), failedMatches = new AtomicLong();
    private final AtomicInteger matchErrorSamples = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger apiDiagnostics = new AtomicInteger(), suppressedApiDiagnostics = new AtomicInteger();
    private volatile String stage = "PREPARATION";
    private volatile long phaseStartedNanos = System.nanoTime();
    private volatile boolean terminal;

    public ActivitySyncLog(MonitoringEventService monitoring, Long communityId, Long communityGameId,
                           Long userId, Instant attemptedAt) {
        this(monitoring, communityId, communityGameId, userId, attemptedAt, Clock.systemUTC());
    }

    public ActivitySyncLog(MonitoringEventService monitoring, Long communityId, Long communityGameId,
                           Long userId, Instant attemptedAt, Clock clock) {
        this.monitoring = monitoring;
        this.communityId = communityId;
        this.communityGameId = communityGameId;
        this.userId = userId;
        this.startedAt = attemptedAt;
        this.clock = clock;
        this.syncId = syncId(communityGameId, attemptedAt);
    }

    // Stable from the existing persisted attempt timestamp: no new activity state column is needed.
    public static String syncId(Long communityGameId, Instant attemptedAt) {
        return UUID.nameUUIDFromBytes((communityGameId + ":" + attemptedAt)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static ActivitySyncLog current() { return CURRENT.get(); }

    static LogContext.Scope bind(ActivitySyncLog operation) {
        ActivitySyncLog previous = CURRENT.get();
        if (operation == null) CURRENT.remove(); else CURRENT.set(operation);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public LogContext.Scope open() {
        var binding = bind(this);
        var context = LogContext.scope(Map.of("syncId", syncId, "communityId", communityId,
                "communityGameId", communityGameId, "userId", userId, "jobName", "ACTIVITY_SYNC", "stage", stage));
        return () -> { context.close(); binding.close(); };
    }

    public synchronized void enrich(Map<String, ?> values) { details.putAll(values); }

    public void stage(String value) { stage = value; phaseStartedNanos = System.nanoTime(); LogContext.put("stage", value); }

    public void apiAttempt(String endpoint, int retryCount) {
        retries.accumulateAndGet(retryCount, Math::max);
        if (endpoint.startsWith("PLAYER_BY_")) playerApiCalls.incrementAndGet();
        if (endpoint.equals("MATCH")) matchApiCalls.incrementAndGet();
    }

    /** Keep an upstream outage from producing thousands of retry/error lines for one activity job. */
    public boolean allowApiDiagnostics() {
        if (!terminal && apiDiagnostics.incrementAndGet() <= 20) return true;
        suppressedApiDiagnostics.incrementAndGet();
        return false;
    }

    public void cacheHit() { cacheHits.incrementAndGet(); }
    public void sharedFetch() { sharedFetches.incrementAndGet(); }
    public void matchResult(boolean found) { (found ? successfulMatches : missingMatches).incrementAndGet(); }

    public Map<String, Object> playerMetrics() { return Map.of("apiCalls", playerApiCalls.get()); }

    public Map<String, Object> matchMetrics() {
        return Map.of("apiCalls", matchApiCalls.get(), "cacheHits", cacheHits.get(),
                "sharedFetches", sharedFetches.get(), "successfulMatches", successfulMatches.get(),
                "missingMatches", missingMatches.get(), "failedMatches", failedMatches.get());
    }

    /** Error samples are capped; normal per-match fetches never emit an INFO event. */
    public void matchFailed(String matchId, Throwable failure) {
        failedMatches.incrementAndGet();
        if (terminal || matchErrorSamples.incrementAndGet() > 5) return;
        var values = failureDetails(failure, "MATCH_FETCH");
        values.put("matchId", matchId);
        event(MonitoringSeverity.WARN, MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_FAILED,
                "PUBG 경기 조회 실패 (오류 표본 최대 5건)", values, null);
    }

    public void info(MonitoringEventCode code, String message, Map<String, ?> values) {
        event(MonitoringSeverity.INFO, code, message, values, null);
    }

    public void stageFailed(Throwable failure) {
        MonitoringEventCode code = switch (stage) {
            case "PLAYER_FETCH" -> MonitoringEventCode.ACTIVITY_SYNC_PLAYER_FETCH_FAILED;
            case "MATCH_FETCH" -> MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_FAILED;
            case "DB_SAVE" -> MonitoringEventCode.ACTIVITY_SYNC_SAVE_FAILED;
            default -> null;
        };
        if (code != null) {
            var values = failureDetails(failure, stage);
            values.put("elapsedMs", (System.nanoTime() - phaseStartedNanos) / 1_000_000);
            values.put("retryCount", retries.get());
            if (stage.equals("PLAYER_FETCH")) values.putAll(playerMetrics());
            if (stage.equals("MATCH_FETCH")) values.putAll(matchMetrics());
            if (stage.equals("DB_SAVE")) {
                enrich(Map.of("snapshotCount", 0, "successChanged", false));
            }
            event(MonitoringSeverity.ERROR, code, "활동 조회 단계 실패: " + stage, values, null);
        }
    }

    public synchronized void completed() {
        if (terminal) return;
        var values = terminalDetails("SUCCESS");
        event(MonitoringSeverity.INFO, MonitoringEventCode.ACTIVITY_SYNC_COMPLETED,
                "인게임 활동 조회가 완료되었습니다.", values, null);
        terminal = true;
    }

    public synchronized void failed(Throwable failure) {
        if (terminal) return;
        var values = terminalDetails("FAILED");
        values.put("stackTrace", stackTrace(failure));
        values.putAll(failureDetails(failure, stage));
        values.put("retryCount", retries.get());
        event(MonitoringSeverity.ERROR, MonitoringEventCode.ACTIVITY_SYNC_FAILED,
                "인게임 활동 조회 실패: " + stage, values, failure);
        terminal = true;
        FailureLogContext.markLogged(failure);
    }

    public synchronized void superseded() {
        if (terminal) return;
        info(MonitoringEventCode.ACTIVITY_SYNC_SUPERSEDED, "새 조회 작업으로 대체되어 결과를 저장하지 않았습니다.",
                terminalDetails("SUPERSEDED"));
        terminal = true;
    }

    private Map<String, Object> terminalDetails(String status) {
        var values = new LinkedHashMap<String, Object>();
        values.put("syncStatus", status);
        values.put("endedAt", clock.instant().toString());
        values.put("durationMs", (System.nanoTime() - startedNanos) / 1_000_000);
        values.put("playerApiCalls", playerApiCalls.get());
        values.put("matchApiCalls", matchApiCalls.get());
        values.put("cacheHits", cacheHits.get());
        values.put("failedMatches", failedMatches.get());
        if (suppressedApiDiagnostics.get() > 0) values.put("suppressedApiDiagnostics", suppressedApiDiagnostics.get());
        return values;
    }

    private synchronized void event(MonitoringSeverity severity, MonitoringEventCode code, String message,
                                    Map<String, ?> values, Throwable failure) {
        if (terminal) return;
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("syncId", syncId);
        metadata.put("communityGameId", communityGameId);
        metadata.put("startedAt", startedAt.toString());
        metadata.put("stage", stage);
        metadata.putAll(values);
        details.forEach(metadata::putIfAbsent);
        var logger = switch (severity) { case INFO -> log.atInfo(); case WARN -> log.atWarn(); case ERROR -> log.atError(); };
        if (failure != null) logger.setCause(failure);
        var summary = new LinkedHashMap<>(metadata);
        summary.remove("stackTrace");
        logger.log("{} syncId={} communityId={} communityGameId={} userId={} {} - {}",
                code, syncId, communityId, communityGameId, userId, SafeLogText.limited(summary.toString(), 3000), message);
        switch (severity) {
            case INFO -> monitoring.recordInfo(MonitoringCategory.SYSTEM, code, message, communityId, userId, syncId, metadata);
            case WARN -> monitoring.recordWarn(MonitoringCategory.SYSTEM, code, message, communityId, userId, syncId, metadata);
            case ERROR -> monitoring.recordError(MonitoringCategory.SYSTEM, code, message, communityId, userId, syncId, metadata);
        }
    }

    public static Map<String, Object> failureDetails(Throwable failure, String stage) {
        var values = new LinkedHashMap<String, Object>();
        values.put("failureStage", stage);
        values.put("exceptionClass", failure.getClass().getName());
        values.put("errorMessage", SafeLogText.limited(failure.getMessage(), 700));
        boolean pubg = false, database = stage.equals("DB_SAVE") || stage.equals("FAILURE_STATE_SAVE"), timeout = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PubgApiException api) {
                pubg = true;
                values.put("pubgErrorCode", api.getErrorCode().name());
                if (api.getUpstreamStatus() != null) values.put("upstreamStatus", api.getUpstreamStatus());
                timeout |= api.getErrorCode().name().equals("PUBG_TIMEOUT");
            }
            if (cause instanceof RestClientResponseException response) {
                values.putIfAbsent("upstreamStatus", response.getStatusCode().value());
            }
            if (cause instanceof ResponseStatusException response) values.putIfAbsent("status", response.getStatusCode().value());
            database |= cause instanceof DataAccessException || cause instanceof java.sql.SQLException
                    || cause instanceof jakarta.persistence.PersistenceException;
            timeout |= cause instanceof ResourceAccessException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException;
        }
        values.put("pubgApiError", pubg);
        values.put("databaseError", database);
        values.put("errorType", pubg ? "PUBG_API" : database ? "DATABASE" : "INTERNAL");
        values.put("timeout", timeout);
        values.put("rateLimited", Objects.equals(values.get("upstreamStatus"), 429));
        return values;
    }

    private String stackTrace(Throwable failure) {
        // Bound traversal before string conversion; retain no exception/request/session in event metadata.
        var text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 8 && seen.add(cause) && text.length() < 5000;
             cause = cause.getCause()) {
            if (!text.isEmpty()) text.append("Caused by: ");
            text.append(cause.getClass().getName()).append(": ").append(SafeLogText.limited(cause.getMessage(), 500)).append('\n');
            for (var frame : cause.getStackTrace()) {
                if (text.length() >= 5000) break;
                text.append("\tat ").append(frame).append('\n');
            }
        }
        return SafeLogText.limited(text.toString(), 5000);
    }
}
