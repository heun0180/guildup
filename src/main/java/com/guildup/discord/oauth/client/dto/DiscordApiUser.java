package com.guildup.discord.oauth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Discord의 {@code /users/@me} 응답을 표현하는 내부 DTO다. */
public record DiscordApiUser(
        String id,
        String username,
        @JsonProperty("global_name") String globalName,
        String avatar
) {
}
