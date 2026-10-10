package com.guildup.user.verification;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.util.Map;

/** 로그에 사용자 ID/고정 오류코드만 전달. 공격 시 전역 기록 상한으로 DB 부하를 제한한다. */
@Component
public class EmailVerificationEvents {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationEvents.class);
    private final MonitoringEventService monitoring;
    private final Clock clock;
    private final int limit;
    private long window = Long.MIN_VALUE;
    private int count;
    public EmailVerificationEvents(MonitoringEventService monitoring, Clock clock, EmailVerificationProperties properties) {
        this.monitoring = monitoring; this.clock = clock; limit = properties.maxEventsPerMinute();
    }
    private synchronized boolean reserve() {
        long now = clock.millis();
        if (window == Long.MIN_VALUE || now - window >= 60000) { window = now; count = 0; }
        if (count >= limit) return false;
        count++;
        return true;
    }
    public void record(MonitoringEventCode code, Long userId) {
        if (!reserve()) return;
        try {
            if (code == MonitoringEventCode.EMAIL_VERIFICATION_COMPLETED)
                monitoring.recordInfo(MonitoringCategory.SECURITY, code, "Email ownership verified", null, userId, null, Map.of());
            else if (code == MonitoringEventCode.EMAIL_VERIFICATION_MAIL_FAILED)
                monitoring.recordError(MonitoringCategory.SECURITY, code, "Verification email delivery failed", null, userId, null, Map.of());
            else monitoring.recordWarn(MonitoringCategory.SECURITY, code, "Email verification request rejected", null, userId, null, Map.of());
        } catch (RuntimeException ignored) { /* 인증/가입 결과는 모니터링 장애와 독립적이다. */ }
    }
    public void mailFailed(Long userId, EmailVerificationMailFailure reason) {
        if (!reserve()) return;
        // DB의 monitoring CHECK 미적용/장애 상황에서도 제한된 안전한 운영 로그로 원인을 확인한다.
        log.warn("Email verification mail delivery failed: reason={} userId={}", reason, userId);
        try {
            monitoring.recordError(MonitoringCategory.SECURITY, MonitoringEventCode.EMAIL_VERIFICATION_MAIL_FAILED,
                    "Verification email delivery failed", null, userId, null, Map.of("reason", reason.name()));
        } catch (RuntimeException ignored) { /* 가입 및 로그인은 모니터링 장애와 독립적이다. */ }
    }
    public void cleanupFailed() {
        try { monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                "Expired verification records cleanup failed", null, null, null, Map.of("jobName", "emailVerificationRetention")); }
        catch (RuntimeException ignored) { /* 정리 작업은 계정 이용과 독립적이다. */ }
    }
}
