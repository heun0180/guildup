package com.guildup.discord.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** JDA에서 요청한 Discord 서버 또는 역할을 찾을 수 없을 때 발생한다. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DiscordResourceNotFoundException extends RuntimeException {

    public DiscordResourceNotFoundException(String message) {
        super(message);
    }
}
