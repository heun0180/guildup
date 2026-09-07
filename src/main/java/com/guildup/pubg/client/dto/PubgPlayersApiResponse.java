package com.guildup.pubg.client.dto;

import java.util.List;

public record PubgPlayersApiResponse(List<PlayerResource> data) {
    public record PlayerResource(
            String id,
            PlayerAttributes attributes,
            PlayerRelationships relationships
    ) {}
    public record PlayerAttributes(String name) {}
    public record PlayerRelationships(MatchRelationship matches) {}
    public record MatchRelationship(List<ResourceReference> data) {}
    public record ResourceReference(String type, String id) {}
}
