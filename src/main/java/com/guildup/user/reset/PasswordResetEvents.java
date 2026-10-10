package com.guildup.user.reset;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.user.verification.EmailVerificationMailFailure;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Map;

/** Only fixed reasons and user IDs. Existing asynchronous monitoring isolates persistence failures. */
@Component
public class PasswordResetEvents {
    private final MonitoringEventService monitoring;
    private final Clock clock;
    private final int limit;
    private long window = Long.MIN_VALUE;
    private int count;
    public PasswordResetEvents(MonitoringEventService monitoring, Clock clock, PasswordResetProperties properties) {
        this.monitoring = monitoring; this.clock = clock; limit = properties.maxEventsPerMinute();
    }
    private synchronized boolean reserve() {
        long now = clock.millis();
        if (window == Long.MIN_VALUE || now - window >= 60000) { window = now; count = 0; }
        if (count >= limit) return false;
        count++; return true;
    }
    public void record(MonitoringEventCode code, Long userId) { record(code, userId, Map.of()); }
    private void record(MonitoringEventCode code, Long userId, Map<String, String> metadata) {
        if (!reserve()) return;
        try {
            if (code == MonitoringEventCode.PASSWORD_RESET_COMPLETED)
                monitoring.recordInfo(MonitoringCategory.SECURITY, code, "Password reset completed", null, userId, null, metadata);
            else if (code == MonitoringEventCode.PASSWORD_RESET_MAIL_FAILED || code == MonitoringEventCode.PASSWORD_RESET_STORAGE_FAILED)
                monitoring.recordError(MonitoringCategory.SECURITY, code, "Password reset operation failed", null, userId, null, metadata);
            else monitoring.recordWarn(MonitoringCategory.SECURITY, code, "Password reset request rejected", null, userId, null, metadata);
        } catch (RuntimeException ignored) { /* Reset never depends on monitoring availability. */ }
    }
    public void mailFailed(Long userId, EmailVerificationMailFailure reason) {
        record(MonitoringEventCode.PASSWORD_RESET_MAIL_FAILED, userId, Map.of("reason", reason.name()));
    }
    public void storageFailed() { record(MonitoringEventCode.PASSWORD_RESET_STORAGE_FAILED, null); }
}
