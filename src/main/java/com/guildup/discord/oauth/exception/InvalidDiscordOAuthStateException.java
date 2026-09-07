package com.guildup.discord.oauth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** OAuth 콜백의 state가 없거나 이미 사용됐거나 만료됐을 때 발생한다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidDiscordOAuthStateException extends RuntimeException {

    public InvalidDiscordOAuthStateException() {
        super("Discord OAuth state is invalid or expired");
    }
}
