package com.guildup.discord.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 짧은 시간 안에 동일한 DM 요청이 반복된 경우 중복 전송을 막는다. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateDiscordDmRequestException extends RuntimeException {
    public DuplicateDiscordDmRequestException() {
        super("The same Discord DM request was already processed recently");
    }
}
