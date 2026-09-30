package com.guildup.pubg.service;

import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Bingo, 랭킹, 활동 통계가 함께 사용할 수 있는 PUBG 경기 수집 경계다. */
@Service
public class PubgMatchSyncService {
    private static final Logger log = LoggerFactory.getLogger(PubgMatchSyncService.class);
    private final PubgPlayerService players;
    private final PubgMatchService matches;
    private final PubgMatchFactProvider facts;
    private final PubgMatchFactQueryService query;
    private final PubgMatchFactWriter writer;
    private final int telemetryConcurrency;
    private final Semaphore telemetryPermits;
    private final ExecutorService telemetryExecutor;
    private final ConcurrentMap<String, CompletableFuture<Void>> telemetryInFlight = new ConcurrentHashMap<>();
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    public PubgMatchSyncService(PubgPlayerService players, PubgMatchService matches, PubgMatchFactProvider facts,
                                PubgMatchFactQueryService query, PubgMatchFactWriter writer,
                                @Value("${pubg.telemetry.fetch-concurrency:3}") int telemetryConcurrency) {
        this.players = players; this.matches = matches; this.facts = facts; this.query = query; this.writer = writer;
        this.telemetryConcurrency = Math.max(1, telemetryConcurrency);
        this.telemetryPermits = new Semaphore(this.telemetryConcurrency, true);
        this.telemetryExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("pubg-telemetry-", 0).factory());
    }

    public SyncResult sync(String shard, Collection<String> accountIds,
                           Predicate<PubgMatch> telemetryRequired, ProgressListener progress) {
        return sync(shard, accountIds, telemetryRequired, false, progress);
    }

    public SyncResult sync(String shard, Collection<String> accountIds,
                           Predicate<PubgMatch> telemetryRequired, boolean upgradeTelemetryFacts,
                           ProgressListener progress) {
        observeTransaction("PLAYER_SYNC");
        List<String> accounts = List.copyOf(new LinkedHashSet<>(accountIds));
        progress.stage("PLAYER_SYNC", 0, accounts.size(), "PUBG 계정의 최신 경기 목록을 확인하고 있습니다.");
        List<PubgPlayer> loadedPlayers = accounts.isEmpty() ? List.of() : players.findByAccountIdsFresh(shard, accounts);
        int playerCalls = (accounts.size() + 9) / 10;
        Set<String> discovered = loadedPlayers.stream().flatMap(player -> player.matchIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        progress.stage("MATCH_DISCOVERY", discovered.size(), discovered.size(), "신규 경기를 확인하고 있습니다.");
        Set<String> existing = query.existingMatchIds(discovered);
        Set<String> newIds = new LinkedHashSet<>(discovered); newIds.removeAll(existing);
        progress.stage("MATCH_FETCH", 0, newIds.size(), "신규 경기 " + newIds.size() + "개를 가져오고 있습니다.");
        observeTransaction("MATCH_FETCH");
        Map<String, PubgMatch> fetched = newIds.isEmpty() ? Map.of() : matches.findUniqueMatches(shard, newIds);

        int insertedMatches = 0, insertedPlayers = 0;
        for (PubgMatch match : fetched.values()) {
            try {
                if (writer.saveMatchIfAbsent(shard, match)) {
                    insertedMatches++;
                    insertedPlayers += match.teams().stream().mapToInt(team -> team.participants().size()).sum();
                }
            } catch (DataIntegrityViolationException race) {
                log.info("PUBG match was inserted concurrently - matchId={}", match.matchId());
            }
        }

        Map<String, PubgMatch> missingTelemetry = new LinkedHashMap<>();
        query.findTelemetryMissing(discovered).forEach(match -> missingTelemetry.put(match.matchId(), match));
        query.findTelemetryMissingForAccounts(accounts).forEach(
                match -> missingTelemetry.putIfAbsent(match.matchId(), match));
        if (upgradeTelemetryFacts) {
            query.findTelemetryUpgradeCandidates(discovered).forEach(
                    match -> missingTelemetry.putIfAbsent(match.matchId(), match));
            query.findTelemetryUpgradeCandidatesForAccounts(accounts).forEach(
                    match -> missingTelemetry.putIfAbsent(match.matchId(), match));
        }
        List<PubgMatch> telemetryMatches = missingTelemetry.values().stream()
                .filter(telemetryRequired).toList();
        progress.stage("TELEMETRY_FETCH", 0, telemetryMatches.size(),
                "Telemetry 0 / " + telemetryMatches.size());
        Counters counters = fetchTelemetry(telemetryMatches, progress);
        return new SyncResult(accounts.size(), playerCalls, discovered.size(), existing.size(), newIds.size(),
                newIds.size(), Math.max(0, newIds.size() - fetched.size()), telemetryMatches.size(), counters.failures,
                insertedMatches, insertedPlayers, counters.kills);
    }

    private Counters fetchTelemetry(List<PubgMatch> values, ProgressListener progress) {
        if (values.isEmpty()) return new Counters();
        Counters total = new Counters();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            for (int i = 0; i < values.size(); i++) {
                try {
                    PubgMatchFactWriter.StoredCounts count = fetchTelemetryOnce(values.get(i));
                    total.players += count.players(); total.kills += count.kills();
                } catch (RuntimeException exception) {
                    total.failures++;
                    logTelemetryFailure(values.get(i).matchId(), exception);
                }
                progress.stage("TELEMETRY_FETCH", i + 1, values.size(),
                        "Telemetry " + (i + 1) + " / " + values.size());
            }
            return total;
        }
        CompletionService<TelemetryOutcome> completions = new ExecutorCompletionService<>(telemetryExecutor);
        values.forEach(match -> completions.submit(() -> {
            try {
                return TelemetryOutcome.success(match.matchId(), fetchTelemetryOnce(match));
            } catch (RuntimeException exception) {
                return TelemetryOutcome.failure(match.matchId(), exception);
            }
        }));
        for (int completed = 1; completed <= values.size(); completed++) {
            try {
                TelemetryOutcome outcome = completions.take().get();
                if (outcome.failure() == null) {
                    total.players += outcome.counts().players();
                    total.kills += outcome.counts().kills();
                } else {
                    total.failures++;
                    logTelemetryFailure(outcome.matchId(), outcome.failure());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("PUBG Telemetry 수집이 중단되었습니다.", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("PUBG Telemetry 작업 결과를 확인하지 못했습니다.", exception.getCause());
            }
            progress.stage("TELEMETRY_FETCH", completed, values.size(),
                    "Telemetry " + completed + " / " + values.size());
        }
        return total;
    }

    private PubgMatchFactWriter.StoredCounts fetchTelemetryOnce(PubgMatch match) {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> current = telemetryInFlight.putIfAbsent(match.matchId(), mine);
        if (current != null) {
            current.join();
            return new PubgMatchFactWriter.StoredCounts(0, 0);
        }
        try {
            observeTransaction("TELEMETRY_FETCH");
            acquireTelemetryPermit();
            Map<String, PlayerMatchFacts> parsed;
            try {
                // parser는 Match의 전체 participant fact를 만든다. community 관련 값은 조회 시점에 재구성한다.
                parsed = facts.facts(match, Set.of());
                PubgMatchFactWriter.StoredCounts result = writer.saveTelemetry(match.matchId(), parsed);
                mine.complete(null);
                return result;
            } finally {
                telemetryPermits.release();
            }
        } catch (RuntimeException exception) {
            mine.completeExceptionally(exception);
            throw exception;
        } finally {
            telemetryInFlight.remove(match.matchId(), mine);
        }
    }

    private void observeTransaction(String stage) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            log.debug("PUBG API called inside a caller-owned transaction - stage={}", stage);
    }

    private void acquireTelemetryPermit() {
        try {
            telemetryPermits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PUBG Telemetry 수집이 중단되었습니다.", exception);
        }
    }

    private void logTelemetryFailure(String matchId, RuntimeException exception) {
        Throwable root = rootCause(exception);
        log.warn("PUBG telemetry fetch failed - matchId={} exceptionClass={} rootCause={} httpStatus={} " +
                        "reason={} attempt=1 timestamp={}", matchId, exception.getClass().getName(),
                root.getClass().getName(), httpStatus(exception), safeReason(root), Instant.now());
        if (monitoring == null) return;
        Integer status = responseStatus(exception);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpoint", "TELEMETRY");
        metadata.put("matchId", matchId);
        if (status != null) metadata.put("status", status);
        metadata.put("exceptionClass", root.getClass().getSimpleName());
        if (status != null && status == 429) {
            monitoring.recordWarn(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_RATE_LIMIT,
                    "PUBG Telemetry rate limit reached", null, null, "matchId=" + matchId, metadata);
        } else if (causedBy(exception, ResourceAccessException.class)) {
            monitoring.recordWarn(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_TIMEOUT,
                    "PUBG Telemetry request timed out", null, null, "matchId=" + matchId, metadata);
        } else if (status != null && status >= 500) {
            monitoring.recordWarn(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_SERVER_ERROR,
                    "PUBG Telemetry server error", null, null, "matchId=" + matchId, metadata);
        } else if (status != null && status >= 400) {
            monitoring.recordWarn(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_CLIENT_ERROR,
                    "PUBG Telemetry client error", null, null, "matchId=" + matchId, metadata);
        }
        monitoring.recordError(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_FAILED,
                "PUBG Telemetry request failed", null, null, "matchId=" + matchId, metadata);
    }

    private Integer responseStatus(Throwable value) {
        Throwable current = value;
        while (current != null) {
            if (current instanceof PubgApiException pubg && pubg.getUpstreamStatus() != null)
                return pubg.getUpstreamStatus();
            if (current instanceof RestClientResponseException response)
                return response.getStatusCode().value();
            current = current.getCause();
        }
        return null;
    }

    private boolean causedBy(Throwable value, Class<? extends Throwable> type) {
        for (Throwable current = value; current != null; current = current.getCause())
            if (type.isInstance(current)) return true;
        return false;
    }

    private Throwable rootCause(Throwable value) {
        Throwable result = value;
        while (result.getCause() != null && result.getCause() != result) result = result.getCause();
        return result;
    }

    private String httpStatus(Throwable value) {
        Throwable current = value;
        while (current != null) {
            if (current instanceof PubgApiException pubg && pubg.getUpstreamStatus() != null)
                return Integer.toString(pubg.getUpstreamStatus());
            if (current instanceof RestClientResponseException response)
                return Integer.toString(response.getStatusCode().value());
            current = current.getCause();
        }
        return "NONE";
    }

    private String safeReason(Throwable value) {
        String message = value.getMessage();
        if (message == null || message.isBlank()) return value.getClass().getSimpleName();
        String sanitized = message.replaceAll("https?://\\S+", "[telemetry-url]")
                .replaceAll("(?i)(authorization|api[-_ ]?key)[:= ]+\\S+", "$1=[redacted]");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }

    @PreDestroy
    void shutdownExecutor() { telemetryExecutor.shutdown(); }

    public interface ProgressListener {
        void stage(String stage, int completed, int total, String message);
        static ProgressListener noop() { return (stage, completed, total, message) -> {}; }
    }
    public record SyncResult(int linkedAccounts, int playerApiCalls, int discoveredMatchIds, int existingDbMatches,
                             int newMatchIds, int matchApiCalls, int matchFailures, int telemetryApiCalls,
                             int telemetryFailures, int dbMatchesInserted, int dbPlayerFactsInserted,
                             int dbKillFactsInserted) {}
    private record TelemetryOutcome(String matchId, PubgMatchFactWriter.StoredCounts counts,
                                    RuntimeException failure) {
        static TelemetryOutcome success(String matchId, PubgMatchFactWriter.StoredCounts counts) {
            return new TelemetryOutcome(matchId, counts, null);
        }
        static TelemetryOutcome failure(String matchId, RuntimeException failure) {
            return new TelemetryOutcome(matchId, null, failure);
        }
    }
    private static final class Counters { int players; int kills; int failures; }
}
