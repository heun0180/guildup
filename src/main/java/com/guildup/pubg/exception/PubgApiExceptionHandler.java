package com.guildup.pubg.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** PUBG 장애와 요청 제한의 안전한 설명을 API 응답에 포함한다. */
@RestControllerAdvice
public class PubgApiExceptionHandler {
    @ExceptionHandler(PubgApiException.class)
    public ResponseEntity<PubgApiErrorResponse> handle(PubgApiException exception) {
        int status = exception.getStatusCode().value();
        return ResponseEntity.status(exception.getStatusCode())
                .body(new PubgApiErrorResponse(status, exception.getReason()));
    }
}
