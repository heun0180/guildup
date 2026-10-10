package com.guildup.user.verification;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class EmailVerificationRetention {
    private final EmailVerificationTokenRepository tokens;
    private final EmailVerificationProperties properties;
    private final Clock clock;
    private final EmailVerificationEvents events;
    public EmailVerificationRetention(EmailVerificationTokenRepository tokens, EmailVerificationProperties properties,
                                      Clock clock, EmailVerificationEvents events) {
        this.tokens = tokens; this.properties = properties; this.clock = clock; this.events = events;
    }
    @Scheduled(cron = "${auth.email-verification.cleanup-cron:0 40 4 * * *}")
    public void cleanup() {
        try {
            // 한 번에 최대 500행. 하루 발송 예산 및 최근 실패 안내용 기록은 보존한다.
            for (int i = 0; i < 10; i++) {
                var batch = tokens.findByExpiresAtBeforeOrderByExpiresAtAsc(clock.instant().minus(properties.retention()), PageRequest.of(0, 500));
                if (batch.isEmpty()) break;
                tokens.deleteAllInBatch(batch);
                if (batch.size() < 500) break;
            }
        } catch (RuntimeException ignored) { events.cleanupFailed(); }
    }
}
