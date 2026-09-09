package com.guildup.pubg.client.dto;

import java.util.List;
import java.util.Map;

/** 한 게임 모드에서 최대 10명의 시즌 통계를 반환하는 PUBG JSON:API 응답이다. */
public record PubgPlayersSeasonApiResponse(List<PlayerSeasonResource> data) {
    public record PlayerSeasonResource(PlayerSeasonAttributes attributes, Relationships relationships) {}
    public record PlayerSeasonAttributes(Map<String, PubgPlayerSeasonApiResponse.GameModeStats> gameModeStats) {}
    public record Relationships(PlayerRelationship player) {}
    public record PlayerRelationship(ResourceReference data) {}
    public record ResourceReference(String id) {}
}
