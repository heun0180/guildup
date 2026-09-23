package com.guildup.pubg.client;

import com.guildup.pubg.exception.PubgApiErrorCode;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 단일 서버의 모든 PUBG API 요청 시작 시각을 한 곳에서 조절한다. */
@Component
public class PubgApiRequestGovernor {

    private static final long MAX_HEADER_INTERVAL_MILLIS = 60_000;

    private final Clock clock;
    private final Sleeper sleeper;
    private final long minimumIntervalMillis;
    private long headerIntervalMillis;
    private long nextRequestAtMillis;
    private long cooldownUntilMillis;

    @Autowired
    public PubgApiRequestGovernor(
            @Value("${pubg.request-min-interval:250ms}") Duration minimumInterval
    ) {
        this(Clock.systemUTC(), Thread::sleep, minimumInterval);
    }

    PubgApiRequestGovernor(Clock clock, Sleeper sleeper, Duration minimumInterval) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.minimumIntervalMillis = Math.max(0, minimumInterval.toMillis());
        this.headerIntervalMillis = this.minimumIntervalMillis;
    }

    static PubgApiRequestGovernor noOp() {
        return new PubgApiRequestGovernor(Clock.systemUTC(), ignored -> { }, Duration.ZERO);
    }

    public void acquire() {
        long waitMillis;
        synchronized (this) {
            long now = clock.millis();
            long permittedAt = Math.max(now, Math.max(nextRequestAtMillis, cooldownUntilMillis));
            waitMillis = Math.max(0, permittedAt - now);
            nextRequestAtMillis = permittedAt + Math.max(minimumIntervalMillis, headerIntervalMillis);
        }
        if (waitMillis == 0) return;
        try {
            sleeper.sleep(waitMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PubgApiException(
                    PubgApiErrorCode.LOCAL_COOLDOWN,
                    "PUBG API 요청 대기가 중단되었습니다.", exception, null, false
            );
        }
    }

    public synchronized void observe(HttpHeaders headers) {
        if (headers == null) return;
        Long remaining = positiveLong(headers.getFirst("X-RateLimit-Remaining"));
        Instant resetAt = resetAt(headers);
        if (remaining == null || resetAt == null) return;
        if (remaining <= 0) {
            cooldownUntilMillis = Math.max(cooldownUntilMillis, resetAt.toEpochMilli());
            return;
        }
        long windowMillis = resetAt.toEpochMilli() - clock.millis();
        if (windowMillis <= 0) return;
        headerIntervalMillis = Math.max(
                minimumIntervalMillis,
                Math.min(MAX_HEADER_INTERVAL_MILLIS, divideRoundingUp(windowMillis, remaining))
        );
    }

    public synchronized void cooldownUntil(Instant retryAt) {
        if (retryAt != null) cooldownUntilMillis = Math.max(cooldownUntilMillis, retryAt.toEpochMilli());
    }

    private Instant resetAt(HttpHeaders headers) {
        String value = headers.getFirst("X-RateLimit-Reset");
        if (value == null) return null;
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long positiveLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long divideRoundingUp(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
