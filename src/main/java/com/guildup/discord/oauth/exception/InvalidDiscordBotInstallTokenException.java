package com.guildup.discord.oauth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 봇 설치 확인용 토큰이 없거나 만료됐을 때 발생한다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidDiscordBotInstallTokenException extends RuntimeException {

    public InvalidDiscordBotInstallTokenException() {
        super("Discord bot install token is invalid or expired");
    }
}
