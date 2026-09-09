package com.guildup.pubg.client.dto;

import java.util.List;

/** PUBG 시즌 목록 JSON:API 응답이다. */
public record PubgSeasonsApiResponse(List<SeasonResource> data) {
    public record SeasonResource(String id, SeasonAttributes attributes) {}
    public record SeasonAttributes(boolean isCurrentSeason, boolean isOffseason) {}
}
