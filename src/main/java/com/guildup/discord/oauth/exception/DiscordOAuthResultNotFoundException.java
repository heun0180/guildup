package com.guildup.discord.oauth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** OAuth 결과 ID가 없거나, 다른 커뮤니티의 결과이거나, 유효 시간이 지났을 때 발생한다. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DiscordOAuthResultNotFoundException extends RuntimeException {

    public DiscordOAuthResultNotFoundException() {
        super("Discord OAuth result is invalid or expired");
    }
}
