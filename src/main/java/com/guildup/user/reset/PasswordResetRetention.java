package com.guildup.user.reset;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class PasswordResetRetention {
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetProperties properties;
    private final PasswordResetEvents events;
    private final Clock clock;
    public PasswordResetRetention(PasswordResetTokenRepository tokens, PasswordResetProperties properties, PasswordResetEvents events, Clock clock) {
        this.tokens = tokens; this.properties = properties; this.events = events; this.clock = clock;
    }
    @Scheduled(cron = "${auth.password-reset.cleanup-cron:0 20 * * * *}")
    public void cleanup() {
        try {
            for (int i = 0; i < 10; i++) {
                var batch = tokens.findByExpiresAtBeforeOrderByExpiresAtAsc(clock.instant().minus(properties.retention()), PageRequest.of(0, 500));
                if (batch.isEmpty()) break;
                tokens.deleteAllInBatch(batch);
                if (batch.size() < 500) break;
            }
        } catch (RuntimeException unavailable) { events.storageFailed(); }
    }
}
