package com.guildup.pubg.client.dto;

import java.time.Instant;
import java.util.List;

public record PubgMatchApiResponse(MatchResource data, List<IncludedResource> included) {
    public record MatchResource(String id, MatchAttributes attributes) {}
    public record MatchAttributes(Instant createdAt, String gameMode) {}
    public record IncludedResource(
            String type,
            String id,
            ParticipantAttributes attributes,
            RosterRelationships relationships
    ) {}
    public record ParticipantAttributes(ParticipantStats stats) {}
    public record ParticipantStats(String playerId, String name) {}
    public record RosterRelationships(ParticipantRelationship participants) {}
    public record ParticipantRelationship(List<ResourceReference> data) {}
    public record ResourceReference(String type, String id) {}
}
