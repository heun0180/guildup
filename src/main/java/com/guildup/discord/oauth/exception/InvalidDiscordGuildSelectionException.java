package com.guildup.discord.oauth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 사용자가 OAuth로 검증받지 않은 Discord 서버를 선택해 설치하려 할 때 발생한다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidDiscordGuildSelectionException extends RuntimeException {

    public InvalidDiscordGuildSelectionException() {
        super("Selected Discord guild was not verified by OAuth");
    }
}
