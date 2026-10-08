package com.guildup.user.auth.security;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Map;

/** Per-identity sampling in the store plus a global write ceiling prevent attack-driven DB log floods. */
@Component
public class LoginSecurityEvents {
    private final MonitoringEventService monitoring;
    private final Clock clock;
    private final int maxPerMinute;
    private long windowStart = Long.MIN_VALUE;
    private int emitted;

    public LoginSecurityEvents(MonitoringEventService monitoring, Clock clock,
                              @Value("${security.login.max-events-per-minute:60}") int maxPerMinute) {
        if (maxPerMinute < 1 || maxPerMinute > 1000) throw new IllegalArgumentException("Invalid security event limit");
        this.monitoring = monitoring; this.clock = clock; this.maxPerMinute = maxPerMinute;
    }

    private synchronized boolean reserve() {
        long now = clock.millis();
        if (windowStart == Long.MIN_VALUE || now - windowStart >= 60000) { windowStart = now; emitted = 0; }
        if (emitted >= maxPerMinute) return false;
        emitted++;
        return true;
    }

    public void record(LoginAttemptStore.Signal signal, String accountId, String ipId) {
        if (!reserve()) return;
        MonitoringEventCode code = switch (signal.kind()) {
            case REPEATED_FAILURE -> MonitoringEventCode.LOGIN_REPEATED_FAILURE;
            case RATE_LIMITED -> MonitoringEventCode.LOGIN_RATE_LIMITED;
            case IP_VOLUME -> MonitoringEventCode.LOGIN_IP_VOLUME;
            case MULTI_ACCOUNT -> MonitoringEventCode.LOGIN_MULTI_ACCOUNT;
            case SUSPECTED_ATTACK -> MonitoringEventCode.LOGIN_ATTACK_SUSPECTED;
        };
        try {
            monitoring.recordWarn(MonitoringCategory.SECURITY, code, "Email login protection signal",
                    null, null, ipId, Map.of("scope", signal.scope(), "accountIdHash", accountId,
                            "ipIdHash", ipId, "requestCount", signal.requestCount(), "failureCount", signal.failureCount(),
                            "distinctAccounts", signal.distinctAccounts(), "rateLimited", signal.rateLimited(),
                            "retryAfterSeconds", signal.retryAfterSeconds(), "riskLevel",
                            signal.kind() == LoginAttemptStore.Kind.MULTI_ACCOUNT || signal.kind() == LoginAttemptStore.Kind.SUSPECTED_ATTACK ? "HIGH" : "MEDIUM"));
        } catch (RuntimeException ignored) { /* Observability never replaces authentication's result. */ }
    }
}
