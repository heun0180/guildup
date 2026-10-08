package com.guildup.user.auth.exception;

import org.springframework.http.HttpStatus;

public class LoginRateLimitedException extends AuthException {
    private final long retryAfterSeconds;
    public LoginRateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "로그인 시도가 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
