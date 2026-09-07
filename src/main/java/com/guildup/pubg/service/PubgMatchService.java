package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgMatch;
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

/** 한 요청에서 같은 Match ID를 한 번만 조회해 모든 클랜원 판정에 재사용한다. */
@Service
public class PubgMatchService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final PubgApiClient apiClient;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<MatchCacheKey, CachedMatch> cache = new LinkedHashMap<>();

    @Autowired
    public PubgMatchService(PubgApiClient apiClient) {
        this(apiClient, Clock.systemUTC(), CACHE_TTL);
    }

    PubgMatchService(PubgApiClient apiClient, Clock clock, Duration cacheTtl) {
        this.apiClient = apiClient;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    public synchronized Map<String, PubgMatch> findUniqueMatches(
            String shard,
            Collection<String> matchIds
    ) {
        Map<String, PubgMatch> matches = new LinkedHashMap<>();
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        for (String matchId : new LinkedHashSet<>(matchIds)) {
            MatchCacheKey key = new MatchCacheKey(shard, matchId);
            CachedMatch cached = cache.get(key);
            PubgMatch match;
            if (cached != null && cached.expiresAt().isAfter(now)) {
                match = cached.match().orElse(null);
            } else {
                match = apiClient.getMatch(shard, matchId);
                cache.put(key, new CachedMatch(Optional.ofNullable(match), now.plus(cacheTtl)));
            }
            if (match != null) matches.put(matchId, match);
        }
        return Map.copyOf(matches);
    }

    private record MatchCacheKey(String shard, String matchId) {}

    private record CachedMatch(Optional<PubgMatch> match, Instant expiresAt) {}
}
