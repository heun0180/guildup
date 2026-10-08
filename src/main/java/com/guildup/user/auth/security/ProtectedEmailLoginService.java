package com.guildup.user.auth.security;

import com.guildup.user.auth.dto.EmailLoginRequest;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.exception.LoginRateLimitedException;
import com.guildup.user.auth.service.CredentialAuthService;
import com.guildup.user.auth.service.CredentialPolicy;
import com.guildup.user.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/** The existing credential verifier, sessions and OAuth flows retain their own responsibilities. */
@Service
public class ProtectedEmailLoginService {
    private final CredentialAuthService credentials;
    private final LoginAttemptStore store;
    private final ClientIpResolver ips;
    private final LoginIdentityHasher hasher;
    private final LoginSecurityEvents events;

    public ProtectedEmailLoginService(CredentialAuthService credentials, LoginAttemptStore store,
            ClientIpResolver ips, LoginIdentityHasher hasher, LoginSecurityEvents events) {
        this.credentials = credentials; this.store = store; this.ips = ips; this.hasher = hasher; this.events = events;
    }

    public User login(EmailLoginRequest body, HttpServletRequest request) {
        String email;
        try { email = CredentialPolicy.normalizeEmail(body.email()); }
        catch (AuthException invalid) { email = "invalid-email"; }
        String accountId = hasher.hash("account", email);
        String ipId = hasher.hash("ip", ips.rateLimitAddress(request));
        var decision = store.begin(accountId, ipId);
        decision.signals().forEach(signal -> events.record(signal, accountId, ipId));
        // A blocked request performs no account lookup/bcrypt, regardless of whether the email exists.
        if (!decision.allowed()) throw new LoginRateLimitedException(decision.retryAfterSeconds());
        LoginAttemptStore.Outcome outcome = LoginAttemptStore.Outcome.ABORTED;
        try {
            User user = credentials.login(body);
            outcome = LoginAttemptStore.Outcome.SUCCESS;
            return user;
        } catch (AuthException failure) {
            if ("INVALID_CREDENTIALS".equals(failure.getCode())) outcome = LoginAttemptStore.Outcome.INVALID_CREDENTIALS;
            throw failure;
        } finally {
            store.complete(decision.permit(), outcome).forEach(signal -> events.record(signal, accountId, ipId));
        }
    }
}
