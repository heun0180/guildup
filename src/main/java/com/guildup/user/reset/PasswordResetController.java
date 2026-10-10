package com.guildup.user.reset;

import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.security.*;
import com.guildup.user.auth.service.CredentialPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {
    public static final String ACCEPTED_MESSAGE = "입력하신 이메일로 재설정 안내를 보낼 수 있는 경우 잠시 후 이메일이 발송됩니다.";
    private final PasswordResetRequests requests;
    private final PasswordResetService service;
    private final PasswordResetRequestLimiter limiter;
    private final ClientIpResolver ips;
    private final LoginIdentityHasher hasher;
    private final PasswordResetEvents events;
    private final PasswordEncoder encoder;
    public PasswordResetController(PasswordResetRequests requests, PasswordResetService service, PasswordResetRequestLimiter limiter,
            ClientIpResolver ips, LoginIdentityHasher hasher, PasswordResetEvents events, PasswordEncoder encoder) {
        this.requests = requests; this.service = service; this.limiter = limiter; this.ips = ips;
        this.hasher = hasher; this.events = events; this.encoder = encoder;
    }
    @PostMapping("/request")
    public ResponseEntity<Accepted> request(@RequestBody Request body, HttpServletRequest request) {
        if (reserve(request, false)) requests.accept(body.email());
        // All account types, throttling, queue rejection and mail failures have this exact response.
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Accepted(ACCEPTED_MESSAGE));
    }
    @PostMapping("/validate")
    public ResponseEntity<Void> validate(@RequestBody Validation body, HttpServletRequest request) {
        requireTokenBudget(request); service.validate(body.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody Confirmation body, HttpServletRequest request) {
        requireTokenBudget(request);
        CredentialPolicy.validatePassword(body.password(), body.passwordConfirmation());
        service.validate(body.token()); // Avoid BCrypt work on guessed tokens. Rechecked under lock after encoding.
        String hash = encoder.encode(body.password());
        service.confirmEncoded(body.token(), hash);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private boolean reserve(HttpServletRequest request, boolean tokenRequest) {
        boolean allowed = limiter.reserve(hasher.hash("password-reset-ip", ips.rateLimitAddress(request)), tokenRequest);
        if (!allowed) events.record(MonitoringEventCode.PASSWORD_RESET_ABUSE, null);
        return allowed;
    }
    private void requireTokenBudget(HttpServletRequest request) {
        if (!reserve(request, true)) throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_RESET_RATE_LIMITED", "요청이 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.");
    }
    public record Accepted(String message) {}
    public record Request(String email) { @Override public String toString() { return "PasswordResetRequest"; } }
    public record Validation(String token) { @Override public String toString() { return "PasswordResetValidation"; } }
    public record Confirmation(String token, String password, String passwordConfirmation) {
        @Override public String toString() { return "PasswordResetConfirmation"; }
    }
}
