package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgSeason;
import com.guildup.pubg.model.PubgSeasonStats;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 현재/전 시즌 결정과 플레이어 시즌 통계 합산을 담당한다. */
@Service
public class PubgSeasonService {
    private static final Duration SEASON_CACHE_TTL = Duration.ofDays(30);
    private static final Duration STATS_CACHE_TTL = Duration.ofMinutes(30);

    private final PubgApiClient apiClient;
    private final Clock clock;
    private final Map<String, CachedSeasons> seasonCache = new LinkedHashMap<>();
    private final Map<StatsCacheKey, CachedStats> statsCache = new LinkedHashMap<>();

    public PubgSeasonService(PubgApiClient apiClient, Clock clock) {
        this.apiClient = apiClient;
        this.clock = clock;
    }

    public SeasonPair getCurrentAndPrevious(String shard) {
        List<PubgSeason> seasons = getSeasons(shard).stream()
                .filter(season -> !season.offseason())
                .sorted(Comparator.comparing(PubgSeason::id))
                .toList();
        PubgSeason current = seasons.stream().filter(PubgSeason::current).findFirst()
                .orElseThrow(() -> new PubgApiException("현재 PUBG 시즌을 확인할 수 없습니다."));
        PubgSeason previous = seasons.stream()
                .filter(season -> season.id().compareTo(current.id()) < 0)
                .max(Comparator.comparing(PubgSeason::id))
                .orElseThrow(() -> new PubgApiException("이전 PUBG 시즌을 확인할 수 없습니다."));
        return new SeasonPair(current.id(), previous.id());
    }

    public PubgSeasonStats getCombinedStats(String shard, String accountId, List<String> seasonIds) {
        return getCombinedStats(shard, List.of(accountId), seasonIds).get(accountId);
    }

    /** 캐시되지 않은 스쿼드 통계는 10명 단위 배치 API로 조회한다. */
    public synchronized Map<String, PubgSeasonStats> getCombinedStats(
            String shard,
            List<String> accountIds,
            List<String> seasonIds
    ) {
        List<String> uniqueAccountIds = new ArrayList<>(new LinkedHashSet<>(accountIds));
        Instant now = clock.instant();
        statsCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Map<String, PubgSeasonStats> combined = new LinkedHashMap<>();
        uniqueAccountIds.forEach(id -> combined.put(id, PubgSeasonStats.empty(id)));

        for (String seasonId : seasonIds) {
            Map<String, PubgSeasonStats> seasonStats = loadSeasonStats(
                    shard, uniqueAccountIds, seasonId, now
            );
            for (String accountId : uniqueAccountIds) {
                PubgSeasonStats total = combined.get(accountId);
                PubgSeasonStats current = seasonStats.getOrDefault(
                        accountId, PubgSeasonStats.empty(accountId)
                );
                combined.put(accountId, new PubgSeasonStats(
                        accountId,
                        total.damageDealt() + current.damageDealt(),
                        total.roundsPlayed() + current.roundsPlayed()
                ));
            }
        }
        return combined;
    }

    private Map<String, PubgSeasonStats> loadSeasonStats(
            String shard,
            List<String> accountIds,
            String seasonId,
            Instant now
    ) {
        Map<String, PubgSeasonStats> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String accountId : accountIds) {
            CachedStats cached = statsCache.get(new StatsCacheKey(shard, accountId, seasonId));
            if (cached == null || !cached.expiresAt().isAfter(now)) missing.add(accountId);
            else result.put(accountId, cached.stats());
        }

        for (int start = 0; start < missing.size(); start += PubgApiClient.MAX_PLAYERS_PER_REQUEST) {
            List<String> batch = missing.subList(start,
                    Math.min(start + PubgApiClient.MAX_PLAYERS_PER_REQUEST, missing.size()));
            result.putAll(apiClient.getPlayersSeasonStats(shard, batch, seasonId));
        }
        for (String accountId : missing) {
            PubgSeasonStats stats = result.getOrDefault(accountId, PubgSeasonStats.empty(accountId));
            statsCache.put(new StatsCacheKey(shard, accountId, seasonId),
                    new CachedStats(stats, now.plus(STATS_CACHE_TTL)));
        }
        return result;
    }

    private synchronized List<PubgSeason> getSeasons(String shard) {
        Instant now = clock.instant();
        CachedSeasons cached = seasonCache.get(shard);
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.seasons();
        List<PubgSeason> seasons = apiClient.getSeasons(shard);
        seasonCache.put(shard, new CachedSeasons(seasons, now.plus(SEASON_CACHE_TTL)));
        return seasons;
    }

    public record SeasonPair(String currentSeasonId, String previousSeasonId) {}
    private record CachedSeasons(List<PubgSeason> seasons, Instant expiresAt) {}
    private record StatsCacheKey(String shard, String accountId, String seasonId) {}
    private record CachedStats(PubgSeasonStats stats, Instant expiresAt) {}
}
