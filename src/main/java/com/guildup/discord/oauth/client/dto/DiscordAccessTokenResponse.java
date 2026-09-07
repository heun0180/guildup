package com.guildup.discord.oauth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Discord 토큰 교환 API의 JSON 응답을 역직렬화하는 내부 DTO다. */
public record DiscordAccessTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope
) {
}
