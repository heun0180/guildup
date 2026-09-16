package com.guildup.pubg.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record PubgMatchApiResponse(MatchResource data, List<IncludedResource> included) {
    public record MatchResource(String id, MatchAttributes attributes, MatchRelationships relationships) {}
    public record MatchAttributes(Instant createdAt, String gameMode, String mapName) {}
    public record MatchRelationships(AssetRelationship assets) {}
    public record AssetRelationship(List<ResourceReference> data) {}
    public record IncludedResource(
            String type,
            String id,
            ParticipantAttributes attributes,
            RosterRelationships relationships
    ) {}
    public record ParticipantAttributes(ParticipantStats stats,
                                        @JsonProperty("URL") String url,
                                        String name) {}
    public record ParticipantStats(
            String playerId, String name, Integer kills, Double damageDealt,
            Integer assists, Integer DBNOs, Integer headshotKills, Integer heals,
            Integer boosts, Integer revives, Integer roadKills, Integer winPlace,
            Double timeSurvived, Double walkDistance, Double rideDistance, Double swimDistance,
            Double longestKill
    ) {}
    public record RosterRelationships(ParticipantRelationship participants) {}
    public record ParticipantRelationship(List<ResourceReference> data) {}
    public record ResourceReference(String type, String id) {}
}
