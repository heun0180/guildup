package com.guildup.pubg.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** PUBG 플레이어 시즌 통계 JSON:API 응답이다. */
public record PubgPlayerSeasonApiResponse(PlayerSeasonResource data) {
    public record PlayerSeasonResource(String id, PlayerSeasonAttributes attributes) {}
    public record PlayerSeasonAttributes(Map<String, GameModeStats> gameModeStats) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameModeStats(double damageDealt, long roundsPlayed) {}
}
