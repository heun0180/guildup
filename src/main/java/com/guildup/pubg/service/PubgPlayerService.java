package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgPlayer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** PUBG 선수 조회를 API 제한인 10명 단위로 나눠 호출한다. */
@Service
public class PubgPlayerService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final PubgApiClient apiClient;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<PlayerCacheKey, CachedPlayer> cache = new LinkedHashMap<>();

    @Autowired
    public PubgPlayerService(PubgApiClient apiClient) {
        this(apiClient, Clock.systemUTC(), CACHE_TTL);
    }

    PubgPlayerService(PubgApiClient apiClient, Clock clock, Duration cacheTtl) {
        this.apiClient = apiClient;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    public List<PubgPlayer> findByNames(String shard, List<String> playerNames) {
        return findInBatches(shard, playerNames, true);
    }

    public List<PubgPlayer> findByAccountIds(String shard, List<String> accountIds) {
        return findInBatches(shard, accountIds, false);
    }

    private synchronized List<PubgPlayer> findInBatches(
            String shard,
            List<String> values,
            boolean byName
    ) {
        List<String> uniqueValues = new ArrayList<>(new LinkedHashSet<>(values));
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Map<String, PubgPlayer> playersByLookupValue = new LinkedHashMap<>();
        List<String> missingValues = new ArrayList<>();

        for (String value : uniqueValues) {
            String normalizedValue = normalize(value, byName);
            CachedPlayer cached = cache.get(new PlayerCacheKey(shard, byName, normalizedValue));
            if (cached == null || !cached.expiresAt().isAfter(now)) {
                missingValues.add(value);
                continue;
            }
            cached.player().ifPresent(player -> playersByLookupValue.put(normalizedValue, player));
        }

        for (int start = 0; start < missingValues.size(); start += PubgApiClient.MAX_PLAYERS_PER_REQUEST) {
            List<String> batch = missingValues.subList(
                    start,
                    Math.min(start + PubgApiClient.MAX_PLAYERS_PER_REQUEST, missingValues.size())
            );
            List<PubgPlayer> loaded = byName
                    ? apiClient.getPlayersByNames(shard, batch)
                    : apiClient.getPlayersByAccountIds(shard, batch);
            Map<String, PubgPlayer> loadedByLookupValue = new LinkedHashMap<>();
            for (PubgPlayer player : loaded) {
                String lookupValue = byName ? player.name() : player.accountId();
                if (lookupValue != null) {
                    loadedByLookupValue.put(normalize(lookupValue, byName), player);
                }
            }
            for (String value : batch) {
                String normalizedValue = normalize(value, byName);
                PubgPlayer player = loadedByLookupValue.get(normalizedValue);
                cache.put(
                        new PlayerCacheKey(shard, byName, normalizedValue),
                        new CachedPlayer(Optional.ofNullable(player), now.plus(cacheTtl))
                );
                if (player != null) {
                    playersByLookupValue.put(normalizedValue, player);
                    cachePlayerAliases(shard, player, now.plus(cacheTtl));
                }
            }
        }

        return uniqueValues.stream()
                .map(value -> playersByLookupValue.get(normalize(value, byName)))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String normalize(String value, boolean byName) {
        return byName ? value.toLowerCase(Locale.ROOT) : value;
    }

    private void cachePlayerAliases(String shard, PubgPlayer player, Instant expiresAt) {
        if (player.accountId() != null) {
            cache.put(
                    new PlayerCacheKey(shard, false, player.accountId()),
                    new CachedPlayer(Optional.of(player), expiresAt)
            );
        }
        if (player.name() != null) {
            cache.put(
                    new PlayerCacheKey(shard, true, normalize(player.name(), true)),
                    new CachedPlayer(Optional.of(player), expiresAt)
            );
        }
    }

    private record PlayerCacheKey(String shard, boolean byName, String value) {}

    private record CachedPlayer(Optional<PubgPlayer> player, Instant expiresAt) {}
}
