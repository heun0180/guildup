package com.guildup.pubg.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PUBG API 장애, 제한 또는 잘못된 응답을 사용자에게 안전한 503으로 변환한다. */
public class PubgApiException extends ResponseStatusException {
    private final Integer upstreamStatus;
    private final boolean retryable;

    public PubgApiException(String reason) {
        this(reason, null, null, false);
    }

    public PubgApiException(String reason, Throwable cause) {
        this(reason, cause, null, false);
    }

    public PubgApiException(
            String reason,
            Throwable cause,
            Integer upstreamStatus,
            boolean retryable
    ) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
        this.upstreamStatus = upstreamStatus;
        this.retryable = retryable;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
