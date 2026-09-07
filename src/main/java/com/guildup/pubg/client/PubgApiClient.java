package com.guildup.pubg.client;

import com.guildup.pubg.client.dto.PubgMatchApiResponse;
import com.guildup.pubg.client.dto.PubgPlayersApiResponse;
import com.guildup.pubg.config.PubgApiProperties;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgTeam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PUBG JSON:API 응답을 외부 DTO에서 GuildUp 내부 모델로 변환한다. */
@Component
public class PubgApiClient {

    public static final int MAX_PLAYERS_PER_REQUEST = 10;
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

    private List<PubgPlayer> getPlayers(String shard, String filter, List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_PLAYERS_PER_REQUEST) {
            throw new IllegalArgumentException("PUBG Players API는 요청당 최대 10명을 지원합니다.");
        }
        try {
            PubgPlayersApiResponse response = restClient.get()
                    .uri(builder -> builder.path("/shards/{shard}/players")
                            .queryParam(filter, String.join(",", values))
                            .build(shard))
                    .headers(this::setHeaders)
                    .retrieve()
                    .body(PubgPlayersApiResponse.class);
            if (response == null || response.data() == null) return List.of();
            return response.data().stream().map(this::toPlayer).toList();
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        } catch (RestClientException exception) {
            throw new PubgApiException("배틀그라운드 계정 정보를 불러오지 못했습니다.", exception);
        }
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
