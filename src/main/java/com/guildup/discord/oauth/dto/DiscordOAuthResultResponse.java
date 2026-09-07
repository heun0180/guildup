package com.guildup.discord.oauth.dto;

import java.util.List;

/** OAuth 완료 후 연결 화면에 보여줄 사용자 정보와 관리 가능한 서버 목록이다. */
public record DiscordOAuthResultResponse(
        DiscordOAuthUserResponse user,
        List<DiscordManageableGuildResponse> guilds
) {
}
