package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** 활동, 빙고, 킬내기가 함께 쓰는 종료 Match 조회 저장소다. */
@Service
public class PubgMatchService {

    static final Duration SUCCESS_CACHE_TTL = Duration.ofHours(24);
    static final Duration MISSING_CACHE_TTL = Duration.ofMinutes(1);
    static final int MAX_CACHE_SIZE = 10_000;

    private final PubgApiClient apiClient;
    private final Clock clock;
    private final Duration successCacheTtl;
    private final Duration missingCacheTtl;
    private final int maximumSize;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<MatchCacheKey, CachedMatch> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentMap<MatchCacheKey, CompletableFuture<Optional<PubgMatch>>> inFlight =
            new ConcurrentHashMap<>();
    private final int fetchConcurrency;
    private final Semaphore fetchPermits;
    private final ExecutorService fetchExecutor;

    @Autowired
    public PubgMatchService(PubgApiClient apiClient,
                            @Value("${pubg.match.fetch-concurrency:3}") int fetchConcurrency) {
        this(apiClient, Clock.systemUTC(), SUCCESS_CACHE_TTL, MISSING_CACHE_TTL, MAX_CACHE_SIZE, fetchConcurrency);
    }

    PubgMatchService(PubgApiClient apiClient, Clock clock, Duration cacheTtl) {
        this(apiClient, clock, cacheTtl, MISSING_CACHE_TTL, MAX_CACHE_SIZE, 3);
    }

    PubgMatchService(
            PubgApiClient apiClient,
            Clock clock,
            Duration successCacheTtl,
            Duration missingCacheTtl,
            int maximumSize
    ) {
        this(apiClient, clock, successCacheTtl, missingCacheTtl, maximumSize, 3);
    }

    PubgMatchService(
            PubgApiClient apiClient,
            Clock clock,
            Duration successCacheTtl,
            Duration missingCacheTtl,
            int maximumSize,
            int fetchConcurrency
    ) {
        this.apiClient = apiClient;
        this.clock = clock;
        this.successCacheTtl = successCacheTtl;
        this.missingCacheTtl = missingCacheTtl;
        this.maximumSize = maximumSize;
        this.fetchConcurrency = Math.max(1, fetchConcurrency);
        this.fetchPermits = new Semaphore(this.fetchConcurrency, true);
        this.fetchExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("pubg-match-", 0).factory());
    }

    public Map<String, PubgMatch> findUniqueMatches(String shard, Collection<String> matchIds) {
        return findUniqueMatches(shard, matchIds, false);
    }

    /** Player 목록은 fresh로 갱신하되, 이미 받은 종료 Match는 재사용한다. 직전 404만 다시 확인한다. */
    public Map<String, PubgMatch> findUniqueMatchesFresh(String shard, Collection<String> matchIds) {
        return findUniqueMatches(shard, matchIds, true);
    }

    public Map<String, PubgMatch> findUniqueMatchesFresh(
            String shard,
            Collection<String> matchIds,
            Long communityId,
            Long communityGameId
    ) {
        return findUniqueMatches(shard, matchIds, true);
    }

    private Map<String, PubgMatch> findUniqueMatches(
            String shard,
            Collection<String> matchIds,
            boolean refreshMissing
    ) {
        Map<String, PubgMatch> result = new LinkedHashMap<>();
        List<String> uniqueIds = List.copyOf(new LinkedHashSet<>(matchIds));
        if (uniqueIds.isEmpty()) return Map.of();
        List<Future<Optional<PubgMatch>>> futures = uniqueIds.stream()
                .map(matchId -> fetchExecutor.submit(() -> findOne(
                        new MatchCacheKey(shard, matchId), refreshMissing))).toList();
        for (int index = 0; index < uniqueIds.size(); index++) {
            try {
                Optional<PubgMatch> match = futures.get(index).get();
                if (match.isPresent()) result.put(uniqueIds.get(index), match.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PUBG Match 조회가 중단되었습니다.", exception);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(exception.getCause());
            }
        }
        return Map.copyOf(result);
    }

    private Optional<PubgMatch> findOne(MatchCacheKey key, boolean refreshMissing) {
        Optional<PubgMatch> cached = cached(key, refreshMissing);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<Optional<PubgMatch>> created = new CompletableFuture<>();
        CompletableFuture<Optional<PubgMatch>> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) return await(existing);

        try {
            acquire(fetchPermits, "PUBG Match 조회가 중단되었습니다.");
            Optional<PubgMatch> loaded;
            try {
                loaded = Optional.ofNullable(apiClient.getMatch(key.shard(), key.matchId()));
            } finally {
                fetchPermits.release();
            }
            putCache(key, loaded);
            created.complete(loaded);
            return loaded;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private Optional<PubgMatch> cached(MatchCacheKey key, boolean refreshMissing) {
        synchronized (cacheLock) {
            Instant now = clock.instant();
            removeExpired(now);
            CachedMatch value = cache.get(key);
            if (value == null || refreshMissing && value.match().isEmpty()) return null;
            return value.match();
        }
    }

    private void putCache(MatchCacheKey key, Optional<PubgMatch> match) {
        synchronized (cacheLock) {
            Instant now = clock.instant();
            removeExpired(now);
            Duration ttl = match.isPresent() ? successCacheTtl : missingCacheTtl;
            cache.put(key, new CachedMatch(match, now.plus(ttl)));
            while (cache.size() > maximumSize) cache.remove(cache.keySet().iterator().next());
        }
    }

    private void removeExpired(Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private Optional<PubgMatch> await(CompletableFuture<Optional<PubgMatch>> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private void acquire(Semaphore permits, String message) {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    int cacheSize() {
        synchronized (cacheLock) {
            removeExpired(clock.instant());
            return cache.size();
        }
    }

    @PreDestroy
    void shutdownExecutor() { fetchExecutor.shutdown(); }

    private record MatchCacheKey(String shard, String matchId) {}

    private record CachedMatch(Optional<PubgMatch> match, Instant expiresAt) {}
}
