package com.guildup.user.auth.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@org.springframework.core.annotation.Order(0)
public class AuthExceptionHandler {
    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<AuthErrorResponse> limited(LoginRateLimitedException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(new AuthErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(WithdrawalBlockedException.class)
    public ResponseEntity<WithdrawalErrorResponse> blocked(WithdrawalBlockedException exception) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new WithdrawalErrorResponse("COMMUNITY_OWNERSHIP_REQUIRED", exception.getMessage(), exception.getCommunities()));
    }

    public record WithdrawalErrorResponse(String code, String message,
            java.util.List<com.guildup.user.auth.service.AccountWithdrawalService.OwnedCommunity> ownedCommunities) {}

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthErrorResponse> handle(AuthException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .body(new AuthErrorResponse(exception.getCode(), exception.getMessage()));
    }

    public record AuthErrorResponse(String code, String message) {}
}
