package com.guildup.pubg.client;

import com.guildup.pubg.client.dto.PubgMatchApiResponse;
import com.guildup.pubg.client.dto.PubgPlayersApiResponse;
import com.guildup.pubg.client.dto.PubgPlayerSeasonApiResponse;
import com.guildup.pubg.client.dto.PubgPlayersSeasonApiResponse;
import com.guildup.pubg.client.dto.PubgSeasonsApiResponse;
import com.guildup.pubg.config.PubgApiProperties;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgTeam;
import com.guildup.pubg.model.PubgSeason;
import com.guildup.pubg.model.PubgSeasonStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** PUBG JSON:API 응답을 외부 DTO에서 GuildUp 내부 모델로 변환한다. */
@Component
public class PubgApiClient {

    public static final int MAX_PLAYERS_PER_REQUEST = 10;
    public static final String TEAM_MAKER_GAME_MODE = "squad";
    private static final String ACCEPT = "application/vnd.api+json";

    private final RestClient restClient;
    private final PubgApiProperties properties;

    public PubgApiClient(
            @Qualifier("pubgRestClient") RestClient restClient,
            PubgApiProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public List<PubgPlayer> getPlayersByNames(String shard, List<String> playerNames) {
        return getPlayers(shard, "filter[playerNames]", playerNames);
    }

    public List<PubgPlayer> getPlayersByAccountIds(String shard, List<String> accountIds) {
        return getPlayers(shard, "filter[playerIds]", accountIds);
    }

    public PubgMatch getMatch(String shard, String matchId) {
        try {
            PubgMatchApiResponse response = restClient.get()
                    .uri("/shards/{shard}/matches/{matchId}", shard, matchId)
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgMatchApiResponse.class);
            if (response == null || response.data() == null || response.data().attributes() == null) {
                throw new PubgApiException("PUBG 경기 정보가 비어 있습니다.");
            }
            return toMatch(response);
        } catch (HttpClientErrorException.NotFound exception) {
            return null;
        } catch (PubgApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 경기 정보를 불러오지 못했습니다.", exception);
        }
    }

    public List<PubgSeason> getSeasons(String shard) {
        try {
            PubgSeasonsApiResponse response = withRateLimitRetry(() -> restClient.get()
                    .uri("/shards/{shard}/seasons", shard)
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgSeasonsApiResponse.class));
            if (response == null || response.data() == null) return List.of();
            return response.data().stream()
                    .filter(resource -> resource.id() != null && resource.attributes() != null)
                    .map(resource -> new PubgSeason(
                            resource.id(), resource.attributes().isCurrentSeason(),
                            resource.attributes().isOffseason()
                    ))
                    .toList();
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 시즌 정보를 불러오지 못했습니다.", exception);
        }
    }

    /** 한 번의 호출로 플레이어의 해당 시즌 전체 게임 모드를 합산한다. */
    public PubgSeasonStats getPlayerSeasonStats(String shard, String accountId, String seasonId) {
        try {
            PubgPlayerSeasonApiResponse response = withRateLimitRetry(() -> restClient.get()
                    .uri("/shards/{shard}/players/{accountId}/seasons/{seasonId}",
                            shard, accountId, seasonId)
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgPlayerSeasonApiResponse.class));
            if (response == null || response.data() == null
                    || response.data().attributes() == null
                    || response.data().attributes().gameModeStats() == null) {
                return PubgSeasonStats.empty(accountId);
            }
            double damage = response.data().attributes().gameModeStats().values().stream()
                    .mapToDouble(PubgPlayerSeasonApiResponse.GameModeStats::damageDealt)
                    .sum();
            long rounds = response.data().attributes().gameModeStats().values().stream()
                    .mapToLong(PubgPlayerSeasonApiResponse.GameModeStats::roundsPlayed)
                    .sum();
            return new PubgSeasonStats(accountId, damage, rounds);
        } catch (HttpClientErrorException.NotFound exception) {
            return PubgSeasonStats.empty(accountId);
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 시즌 통계를 불러오지 못했습니다.", exception);
        }
    }

    /** 스쿼드 배치 API를 사용해 한 시즌의 최대 10명 통계를 한 번에 조회한다. */
    public Map<String, PubgSeasonStats> getPlayersSeasonStats(
            String shard,
            List<String> accountIds,
            String seasonId
    ) {
        if (accountIds == null || accountIds.isEmpty()) return Map.of();
        if (accountIds.size() > MAX_PLAYERS_PER_REQUEST) {
            throw new IllegalArgumentException("PUBG 시즌 통계 배치 API는 요청당 최대 10명을 지원합니다.");
        }
        Map<String, PubgSeasonStats> result = new LinkedHashMap<>();
        accountIds.forEach(accountId -> result.put(accountId, PubgSeasonStats.empty(accountId)));
        try {
            PubgPlayersSeasonApiResponse response = withRateLimitRetry(() -> restClient.get()
                    .uri(builder -> builder
                            .path("/shards/{shard}/seasons/{seasonId}/gameMode/{gameMode}/players")
                            .queryParam("filter[playerIds]", String.join(",", accountIds))
                            .build(shard, seasonId, TEAM_MAKER_GAME_MODE))
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgPlayersSeasonApiResponse.class));
            if (response == null || response.data() == null) return result;
            for (var resource : response.data()) {
                String accountId = batchAccountId(resource);
                if (accountId == null || !result.containsKey(accountId)
                        || resource.attributes() == null
                        || resource.attributes().gameModeStats() == null) continue;
                double damage = resource.attributes().gameModeStats().values().stream()
                        .mapToDouble(PubgPlayerSeasonApiResponse.GameModeStats::damageDealt).sum();
                long rounds = resource.attributes().gameModeStats().values().stream()
                        .mapToLong(PubgPlayerSeasonApiResponse.GameModeStats::roundsPlayed).sum();
                result.put(accountId, new PubgSeasonStats(accountId, damage, rounds));
            }
            return result;
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 시즌 통계를 불러오지 못했습니다.", exception);
        }
    }

    private List<PubgPlayer> getPlayers(String shard, String filter, List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_PLAYERS_PER_REQUEST) {
            throw new IllegalArgumentException("PUBG Players API는 요청당 최대 10명을 지원합니다.");
        }
        try {
            PubgPlayersApiResponse response = withRateLimitRetry(() -> restClient.get()
                    .uri(builder -> builder.path("/shards/{shard}/players")
                            .queryParam(filter, String.join(",", values))
                            .build(shard))
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgPlayersApiResponse.class));
            if (response == null || response.data() == null) return List.of();
            return response.data().stream().map(this::toPlayer).toList();
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 계정 정보를 불러오지 못했습니다.", exception);
        }
    }

    private String batchAccountId(PubgPlayersSeasonApiResponse.PlayerSeasonResource resource) {
        if (resource.relationships() == null || resource.relationships().player() == null
                || resource.relationships().player().data() == null) return null;
        return resource.relationships().player().data().id();
    }

    /** 429의 reset 헤더가 있으면 해당 시각까지 한 번 기다린 뒤 동일 요청을 재시도한다. */
    private <T> T withRateLimitRetry(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.TooManyRequests exception) {
            long waitMillis = rateLimitWaitMillis(exception.getResponseHeaders());
            if (waitMillis <= 0 || waitMillis > 61_000) {
                throw rateLimitException(exception);
            }
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PubgApiException("PUBG API 요청 대기가 중단되었습니다.", interrupted);
            }
            try {
                return request.get();
            } catch (HttpClientErrorException.TooManyRequests retryFailure) {
                throw rateLimitException(retryFailure);
            }
        }
    }

    private long rateLimitWaitMillis(HttpHeaders headers) {
        if (headers == null) return 0;
        String reset = headers.getFirst("X-RateLimit-Reset");
        if (reset != null) {
            try {
                return Math.max(1_000, Instant.ofEpochSecond(Long.parseLong(reset)).toEpochMilli()
                        - Instant.now().toEpochMilli() + 1_000);
            } catch (NumberFormatException ignored) {
                // Retry-After를 이어서 확인한다.
            }
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null) return 0;
        try {
            return Math.max(1_000, Long.parseLong(retryAfter) * 1_000 + 1_000);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private PubgApiException rateLimitException(HttpClientErrorException.TooManyRequests cause) {
        return new PubgApiException(
                "PUBG API 요청 한도에 도달했습니다. 최대 1분 후 팀 생성을 다시 시도해 주세요.", cause
        );
    }

    private void setHeaders(HttpHeaders headers) {
        headers.setBearerAuth(properties.requireApiKey());
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
    }

    private PubgPlayer toPlayer(PubgPlayersApiResponse.PlayerResource resource) {
        List<String> matches = resource.relationships() == null
                || resource.relationships().matches() == null
                || resource.relationships().matches().data() == null
                ? List.of()
                : resource.relationships().matches().data().stream()
                .map(PubgPlayersApiResponse.ResourceReference::id)
                .toList();
        return new PubgPlayer(
                resource.id(),
                resource.attributes() == null ? null : resource.attributes().name(),
                matches
        );
    }

    private PubgMatch toMatch(PubgMatchApiResponse response) {
        Map<String, PubgParticipant> participants = new LinkedHashMap<>();
        for (var included : safeIncluded(response)) {
            if (!"participant".equals(included.type()) || included.attributes() == null
                    || included.attributes().stats() == null) continue;
            var stats = included.attributes().stats();
            participants.put(included.id(), new PubgParticipant(stats.playerId(), stats.name()));
        }

        List<PubgTeam> teams = safeIncluded(response).stream()
                .filter(included -> "roster".equals(included.type()))
                .map(included -> {
                    var relationship = included.relationships() == null
                            ? null : included.relationships().participants();
                    List<PubgParticipant> members = relationship == null || relationship.data() == null
                            ? List.of()
                            : relationship.data().stream()
                            .map(reference -> participants.get(reference.id()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
                    return new PubgTeam(members);
                })
                .toList();
        var attributes = response.data().attributes();
        return new PubgMatch(response.data().id(), attributes.createdAt(), attributes.gameMode(), teams);
    }

    private List<PubgMatchApiResponse.IncludedResource> safeIncluded(PubgMatchApiResponse response) {
        return response.included() == null ? List.of() : response.included();
    }
}
