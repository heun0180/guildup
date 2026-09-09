package com.guildup.pubg.client;

import com.guildup.pubg.config.PubgApiProperties;
import com.guildup.pubg.exception.PubgApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PubgApiClientTests {

    private static final MediaType PUBG_JSON = MediaType.parseMediaType("application/vnd.api+json");

    @Test
    void batchesPlayerNamesOnTheRequestedKakaoShardAndMapsAccountIds() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.pubg.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PubgApiClient client = new PubgApiClient(
                builder.build(), new PubgApiProperties("secret-key", "https://api.pubg.test")
        );
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/shards/kakao/players")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .isEqualTo("filter%5BplayerNames%5D=sa-gwa,jul-mi"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"type":"player","id":"account.A","attributes":{"name":"sa-gwa"},
                           "relationships":{"matches":{"data":[{"type":"match","id":"match-1"}]}}},
                          {"type":"player","id":"account.B","attributes":{"name":"jul-mi"},
                           "relationships":{"matches":{"data":[]}}}
                        ]}
                        """, PUBG_JSON));

        var players = client.getPlayersByNames("kakao", List.of("sa-gwa", "jul-mi"));

        assertThat(players).extracting(player -> player.accountId())
                .containsExactly("account.A", "account.B");
        assertThat(players.getFirst().matchIds()).containsExactly("match-1");
        server.verify();
    }

    @Test
    void usesSteamShardAndBuildsTeamsFromRosterRelationships() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.pubg.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PubgApiClient client = new PubgApiClient(
                builder.build(), new PubgApiProperties("secret-key", "https://api.pubg.test")
        );
        server.expect(requestTo("https://api.pubg.test/shards/steam/matches/match-1"))
                .andRespond(withSuccess("""
                        {
                          "data":{"type":"match","id":"match-1","attributes":{"createdAt":"2026-09-06T14:14:00Z","gameMode":"squad"}},
                          "included":[
                            {"type":"participant","id":"p1","attributes":{"stats":{"playerId":"account.A","name":"sa-gwa"}}},
                            {"type":"participant","id":"p2","attributes":{"stats":{"playerId":"account.B","name":"jul-mi"}}},
                            {"type":"roster","id":"r1","relationships":{"participants":{"data":[{"type":"participant","id":"p1"},{"type":"participant","id":"p2"}]}}}
                          ]
                        }
                        """, PUBG_JSON));

        var match = client.getMatch("steam", "match-1");

        assertThat(match.playedAt()).isEqualTo(Instant.parse("2026-09-06T14:14:00Z"));
        assertThat(match.gameMode()).isEqualTo("squad");
        assertThat(match.teams()).hasSize(1);
        assertThat(match.teams().getFirst().participants())
                .extracting(participant -> participant.accountId())
                .containsExactly("account.A", "account.B");
        server.verify();
    }

    @Test
    void convertsRateLimitFailuresToServiceUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.pubg.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PubgApiClient client = new PubgApiClient(
                builder.build(), new PubgApiProperties("secret-key", "https://api.pubg.test")
        );
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/shards/kakao/players")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getPlayersByNames("kakao", List.of("sa-gwa")))
                .isInstanceOf(PubgApiException.class)
                .extracting(error -> ((PubgApiException) error).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsCurrentSeasonAndSumsAllGameModesForPlayerStats() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.pubg.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PubgApiClient client = new PubgApiClient(
                builder.build(), new PubgApiProperties("secret-key", "https://api.pubg.test")
        );
        server.expect(requestTo("https://api.pubg.test/shards/kakao/seasons"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"type":"season","id":"season-1","attributes":{"isCurrentSeason":false,"isOffseason":false}},
                          {"type":"season","id":"season-2","attributes":{"isCurrentSeason":true,"isOffseason":false}}
                        ]}
                        """, PUBG_JSON));
        server.expect(requestTo("https://api.pubg.test/shards/kakao/players/account.A/seasons/season-2"))
                .andRespond(withSuccess("""
                        {"data":{"type":"playerSeason","id":"account.A","attributes":{"gameModeStats":{
                          "squad":{"damageDealt":800.5,"roundsPlayed":3},
                          "squad-fpp":{"damageDealt":399.5,"roundsPlayed":1}
                        }}}}
                        """, PUBG_JSON));

        assertThat(client.getSeasons("kakao")).anySatisfy(season -> {
            assertThat(season.id()).isEqualTo("season-2");
            assertThat(season.current()).isTrue();
        });
        var stats = client.getPlayerSeasonStats("kakao", "account.A", "season-2");
        assertThat(stats.damageDealt()).isEqualTo(1_200);
        assertThat(stats.roundsPlayed()).isEqualTo(4);
        server.verify();
    }

    @Test
    void batchesSquadSeasonStatsForTenPlayersInOneRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.pubg.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PubgApiClient client = new PubgApiClient(
                builder.build(), new PubgApiProperties("secret-key", "https://api.pubg.test")
        );
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/shards/kakao/seasons/current/gameMode/squad/players"
                )))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .isEqualTo("filter%5BplayerIds%5D=account.A,account.B"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"type":"playerSeason","attributes":{"gameModeStats":{"squad":{"damageDealt":100,"roundsPlayed":2}}},
                           "relationships":{"player":{"data":{"type":"player","id":"account.A"}}}},
                          {"type":"playerSeason","attributes":{"gameModeStats":{"squad":{"damageDealt":50,"roundsPlayed":1}}},
                           "relationships":{"player":{"data":{"type":"player","id":"account.B"}}}}
                        ]}
                        """, PUBG_JSON));

        Map<String, com.guildup.pubg.model.PubgSeasonStats> stats = client.getPlayersSeasonStats(
                "kakao", List.of("account.A", "account.B"), "current"
        );

        assertThat(stats.get("account.A").damageDealt()).isEqualTo(100);
        assertThat(stats.get("account.A").roundsPlayed()).isEqualTo(2);
        assertThat(stats.get("account.B").damageDealt()).isEqualTo(50);
        server.verify();
    }
}
