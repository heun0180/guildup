package com.guildup.user.verification;

import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/email-verification")
public class EmailVerificationController {
    private final EmailVerificationService service;
    public EmailVerificationController(EmailVerificationService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<EmailVerificationService.Status> status(HttpServletRequest request) {
        return response(HttpStatus.OK, service.status(CurrentUserSession.requireUserId(request.getSession(false))));
    }
    @PostMapping("/resend")
    public ResponseEntity<EmailVerificationService.Status> resend(HttpServletRequest request) {
        return response(HttpStatus.ACCEPTED, service.resend(CurrentUserSession.requireUserId(request.getSession(false))));
    }
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody Confirmation body, HttpServletRequest request) {
        service.confirm(CurrentUserSession.requireUserId(request.getSession(false)), body.token());
        return response(HttpStatus.NO_CONTENT, null);
    }
    private <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
    }
    public record Confirmation(String token) {
        @Override public String toString() { return "EmailVerificationConfirmation"; }
    }
    @ExceptionHandler(EmailVerificationRateLimitedException.class)
    public ResponseEntity<com.guildup.user.auth.exception.AuthExceptionHandler.AuthErrorResponse> limited(EmailVerificationRateLimitedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                .header("Retry-After", Long.toString(exception.getRetryAfter()))
                .body(new com.guildup.user.auth.exception.AuthExceptionHandler.AuthErrorResponse(exception.getCode(), exception.getMessage()));
    }
}
