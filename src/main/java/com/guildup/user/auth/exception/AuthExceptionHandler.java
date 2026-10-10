package com.guildup.user.auth.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.guildup.monitoring.web.AccessDeniedMonitoringFilter;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
@org.springframework.core.annotation.Order(0)
public class AuthExceptionHandler {
    @ExceptionHandler(com.guildup.user.verification.EmailVerificationRateLimitedException.class)
    public ResponseEntity<AuthErrorResponse> verificationLimited(com.guildup.user.verification.EmailVerificationRateLimitedException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .header("Retry-After", Long.toString(exception.getRetryAfter()))
                .body(new AuthErrorResponse(exception.getCode(), exception.getMessage()));
    }

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
    public ResponseEntity<AuthErrorResponse> handle(AuthException exception, HttpServletRequest request) {
        if (exception.getStatus().value() == 403) {
            AccessDeniedMonitoringFilter.mark(request, switch (exception.getCode()) {
                case "EMAIL_VERIFICATION_REQUIRED" -> AccessDeniedMonitoringFilter.Denial.EMAIL_VERIFICATION_REQUIRED;
                case "WITHDRAWAL_VERIFICATION_REQUIRED" -> AccessDeniedMonitoringFilter.Denial.WITHDRAWAL_VERIFICATION_REQUIRED;
                default -> AccessDeniedMonitoringFilter.Denial.APPLICATION_ACCESS_DENIED;
            });
        }
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .body(new AuthErrorResponse(exception.getCode(), exception.getMessage()));
    }

    public record AuthErrorResponse(String code, String message) {}
}
