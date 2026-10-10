package com.guildup.user.verification;

import com.guildup.user.auth.exception.AuthException;
import org.springframework.http.HttpStatus;

public class EmailVerificationRateLimitedException extends AuthException {
    private final long retryAfter;
    public EmailVerificationRateLimitedException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_VERIFICATION_RATE_LIMITED", "인증 요청이 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }
    public long getRetryAfter() { return retryAfter; }
}
