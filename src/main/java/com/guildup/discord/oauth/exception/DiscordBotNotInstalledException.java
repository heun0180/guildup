package com.guildup.discord.oauth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 설치 확인 시 JDA가 대상 Discord 서버를 찾지 못했음을 나타낸다.
 * 사용자가 설치를 아직 끝내지 않았을 수 있으므로 409 Conflict로 응답한다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DiscordBotNotInstalledException extends RuntimeException {

    public DiscordBotNotInstalledException(String guildId) {
        super("GuildUp bot is not available in Discord guild: " + guildId);
    }
}
