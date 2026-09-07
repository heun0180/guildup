package com.guildup.pubg.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PUBG API 장애, 제한 또는 잘못된 응답을 사용자에게 안전한 503으로 변환한다. */
public class PubgApiException extends ResponseStatusException {
    public PubgApiException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    public PubgApiException(String reason, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }
}
