package com.guildup.pubg.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PUBG API 장애, 제한 또는 잘못된 응답을 사용자에게 안전한 503으로 변환한다. */
public class PubgApiException extends ResponseStatusException {
    private final PubgApiErrorCode errorCode;
    private final Integer upstreamStatus;
    private final boolean retryable;

    public PubgApiException(String reason) {
        this(PubgApiErrorCode.PUBG_ERROR, reason, null, null, false);
    }

    public PubgApiException(String reason, Throwable cause) {
        this(PubgApiErrorCode.PUBG_ERROR, reason, cause, null, false);
    }

    public PubgApiException(
            String reason,
            Throwable cause,
            Integer upstreamStatus,
            boolean retryable
    ) {
        this(defaultCode(upstreamStatus), reason, cause, upstreamStatus, retryable);
    }

    public PubgApiException(
            PubgApiErrorCode errorCode,
            String reason,
            Throwable cause,
            Integer upstreamStatus,
            boolean retryable
    ) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
        this.retryable = retryable;
    }

    public PubgApiErrorCode getErrorCode() {
        return errorCode;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    private static PubgApiErrorCode defaultCode(Integer status) {
        if (status != null && status == 429) return PubgApiErrorCode.PUBG_RATE_LIMITED;
        if (status != null && (status == 500 || status == 502 || status == 503 || status == 504)) {
            return PubgApiErrorCode.PUBG_UNAVAILABLE;
        }
        return PubgApiErrorCode.PUBG_ERROR;
    }
}
