package com.guildup.discord.oauth.dto;

/** OAuth 결과에서 조회하거나 참여할 Discord 서버를 지정한다. */
public record DiscordGuildSelectionRequest(
        String oauthResultId,
        String guildId,
        Boolean discardSourceCommunity
) {
    public boolean shouldDiscardSourceCommunity() {
        return Boolean.TRUE.equals(discardSourceCommunity);
    }
}
