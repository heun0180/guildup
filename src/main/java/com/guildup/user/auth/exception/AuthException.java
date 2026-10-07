package com.guildup.user.auth.exception;

import org.springframework.http.HttpStatus;

/** 인증 입력값이나 DB 원문을 메시지에 포함하지 않는다. */
public class AuthException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AuthException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static AuthException discordNotLinked() {
        return new AuthException(HttpStatus.CONFLICT, "DISCORD_NOT_LINKED",
                "Discord 계정이 연결되어 있지 않습니다. Discord 기능을 사용하려면 계정을 연결해 주세요.");
    }
}
