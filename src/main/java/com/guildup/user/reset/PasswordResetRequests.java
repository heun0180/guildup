package com.guildup.user.reset;

import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.CredentialPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.concurrent.Executor;

@Service
public class PasswordResetRequests {
    private final Executor executor;
    private final PasswordResetService service;
    private final PasswordResetEvents events;
    public PasswordResetRequests(@Qualifier("passwordResetRequestExecutor") Executor executor, PasswordResetService service, PasswordResetEvents events) {
        this.executor = executor; this.service = service; this.events = events;
    }
    public void accept(String email) {
        // Validation is account-independent. No SMTP/DB work, waits or caller-runs fallback in the HTTP thread.
        String normalized;
        try { normalized = CredentialPolicy.normalizeEmail(email); }
        catch (AuthException invalidEmail) { return; }
        try {
            executor.execute(() -> {
                try { service.issue(normalized); }
                catch (RuntimeException failure) { events.storageFailed(); }
            });
        } catch (RuntimeException queueFull) { events.record(MonitoringEventCode.PASSWORD_RESET_ABUSE, null); }
    }
}
