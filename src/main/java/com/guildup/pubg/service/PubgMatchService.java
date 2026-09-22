package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/** 한 요청에서 같은 Match ID를 한 번만 조회해 모든 클랜원 판정에 재사용한다. */
@Service
public class PubgMatchService {

    private static final Logger log = LoggerFactory.getLogger(PubgMatchService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 500;

    private final PubgApiClient apiClient;
    private final Clock clock;
    private final Duration cacheTtl;
    private final RetrySleeper retrySleeper;
    private final Map<MatchCacheKey, CachedMatch> cache = new LinkedHashMap<>();

    @Autowired
    public PubgMatchService(PubgApiClient apiClient) {
        this(apiClient, Clock.systemUTC(), CACHE_TTL, Thread::sleep);
    }

    PubgMatchService(PubgApiClient apiClient, Clock clock, Duration cacheTtl) {
        this(apiClient, clock, cacheTtl, Thread::sleep);
    }

    PubgMatchService(
            PubgApiClient apiClient,
            Clock clock,
            Duration cacheTtl,
            RetrySleeper retrySleeper
    ) {
        this.apiClient = apiClient;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.retrySleeper = retrySleeper;
    }

    public synchronized Map<String, PubgMatch> findUniqueMatches(
            String shard,
            Collection<String> matchIds
    ) {
        return findUniqueMatches(shard, matchIds, false);
    }

    /** 정산 시 이전의 일시적 미반영/404 캐시를 사용하지 않고 Match를 다시 확인한다. */
    public synchronized Map<String, PubgMatch> findUniqueMatchesFresh(
            String shard,
            Collection<String> matchIds
    ) {
        return findUniqueMatches(shard, matchIds, true, null, null);
    }

    public synchronized Map<String, PubgMatch> findUniqueMatchesFresh(
            String shard,
            Collection<String> matchIds,
            Long communityId,
            Long communityGameId
    ) {
        return findUniqueMatches(shard, matchIds, true, communityId, communityGameId);
    }

    private Map<String, PubgMatch> findUniqueMatches(String shard, Collection<String> matchIds, boolean refresh) {
        return findUniqueMatches(shard, matchIds, refresh, null, null);
    }

    private Map<String, PubgMatch> findUniqueMatches(
            String shard,
            Collection<String> matchIds,
            boolean refresh,
            Long communityId,
            Long communityGameId
    ) {
        Map<String, PubgMatch> matches = new LinkedHashMap<>();
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        List<String> uniqueMatchIds = List.copyOf(new LinkedHashSet<>(matchIds));
        for (int index = 0; index < uniqueMatchIds.size(); index++) {
            String matchId = uniqueMatchIds.get(index);
            MatchCacheKey key = new MatchCacheKey(shard, matchId);
            CachedMatch cached = refresh ? null : cache.get(key);
            PubgMatch match;
            if (cached != null && cached.expiresAt().isAfter(now)) {
                match = cached.match().orElse(null);
            } else {
                match = getMatchWithRetry(
                        shard, matchId, communityId, communityGameId,
                        index + 1, uniqueMatchIds.size()
                );
                cache.put(key, new CachedMatch(Optional.ofNullable(match), now.plus(cacheTtl)));
            }
            if (match != null) matches.put(matchId, match);
        }
        return Map.copyOf(matches);
    }

    private PubgMatch getMatchWithRetry(
            String shard,
            String matchId,
            Long communityId,
            Long communityGameId,
            int progress,
            int total
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return apiClient.getMatch(shard, matchId);
            } catch (PubgApiException exception) {
                if (!exception.isRetryable() || attempt == MAX_ATTEMPTS) {
                    log.error(
                            "PUBG match request failed - communityId={}, communityGameId={}, stage=MATCH, "
                                    + "matchId={}, progress={}/{}, attempts={}, status={}, exception={}, message={}",
                            communityId, communityGameId, matchId, progress, total, attempt,
                            status(exception), exceptionType(exception), exceptionMessage(exception)
                    );
                    throw exception;
                }
                long backoffMillis = INITIAL_BACKOFF_MILLIS << (attempt - 1);
                log.warn(
                        "PUBG match request retry - communityId={}, communityGameId={}, stage=MATCH, "
                                + "matchId={}, progress={}/{}, attempt={}/{}, status={}, exception={}, "
                                + "message={}, backoffMs={}",
                        communityId, communityGameId, matchId, progress, total, attempt + 1,
                        MAX_ATTEMPTS, status(exception), exceptionType(exception),
                        exceptionMessage(exception), backoffMillis
                );
                waitBeforeRetry(backoffMillis, exception, communityId, communityGameId, matchId, progress, total);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void waitBeforeRetry(
            long backoffMillis,
            PubgApiException cause,
            Long communityId,
            Long communityGameId,
            String matchId,
            int progress,
            int total
    ) {
        try {
            retrySleeper.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            PubgApiException failure = new PubgApiException(
                    "PUBG 경기 재시도 대기가 중단되었습니다.", interrupted,
                    cause.getUpstreamStatus(), false
            );
            log.error(
                    "PUBG match request failed - communityId={}, communityGameId={}, stage=MATCH, "
                            + "matchId={}, progress={}/{}, attempts=1, status={}, exception={}, message={}",
                    communityId, communityGameId, matchId, progress, total, status(failure),
                    failure.getClass().getSimpleName(), failure.getReason()
            );
            throw failure;
        }
    }

    private Object status(PubgApiException exception) {
        return exception.getUpstreamStatus() == null ? "N/A" : exception.getUpstreamStatus();
    }

    private String exceptionType(PubgApiException exception) {
        return diagnosticCause(exception).getClass().getSimpleName();
    }

    private String exceptionMessage(PubgApiException exception) {
        Throwable diagnostic = diagnosticCause(exception);
        String message = diagnostic == exception ? exception.getReason() : diagnostic.getMessage();
        if (message == null) return null;
        String singleLine = message.replaceAll("[\\r\\n\\t]", " ");
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private Throwable diagnosticCause(PubgApiException exception) {
        if (!exception.isRetryable() || exception.getUpstreamStatus() != null) return exception;
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record MatchCacheKey(String shard, String matchId) {}

    private record CachedMatch(Optional<PubgMatch> match, Instant expiresAt) {}
}
