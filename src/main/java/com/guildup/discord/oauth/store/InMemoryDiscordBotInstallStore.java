package com.guildup.discord.oauth.store;

import com.guildup.discord.oauth.exception.InvalidDiscordBotInstallTokenException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 봇 설치 세션을 애플리케이션 메모리에 보관하는 구현체다.
 * 서버 재시작 시 데이터가 사라지며, 각 토큰은 생성 후 10분 동안만 유효하다.
 */
@Component
public class InMemoryDiscordBotInstallStore implements DiscordBotInstallStore {

    // 오래된 설치 요청이 나중에 재사용되지 않도록 유효 시간을 제한한다.
    private static final Duration INSTALL_TOKEN_TTL = Duration.ofMinutes(10);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, ExpiringInstallSession> installSessions = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 운영 환경에서는 UTC 시스템 시간을 사용한다. */
    public InMemoryDiscordBotInstallStore() {
        this(Clock.systemUTC());
    }

    /** 테스트에서 시간을 고정하거나 이동할 수 있도록 Clock을 주입받는 생성자다. */
    InMemoryDiscordBotInstallStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String createInstallToken(DiscordBotInstallSession installSession) {
        // 새 토큰을 만들기 전에 만료된 항목을 정리해 메모리 누적을 줄인다.
        removeExpiredEntries();
        String installToken = randomId();
        installSessions.put(
                installToken,
                new ExpiringInstallSession(installSession, now().plus(INSTALL_TOKEN_TTL))
        );
        return installToken;
    }

    @Override
    public DiscordBotInstallSession getInstallSession(String installToken) {
        ExpiringInstallSession stored = installToken == null ? null : installSessions.get(installToken);
        if (stored == null || stored.expiresAt().isBefore(now())) {
            // 만료된 항목은 발견 즉시 저장소에서도 제거한다.
            if (stored != null) {
                installSessions.remove(installToken, stored);
            }
            throw new InvalidDiscordBotInstallTokenException();
        }
        return stored.installSession();
    }

    @Override
    public void removeInstallToken(String installToken) {
        if (installToken != null) {
            installSessions.remove(installToken);
        }
    }

    /** 예측하기 어려운 256비트 난수를 URL에 안전한 문자열로 변환한다. */
    private String randomId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Clock 접근을 한곳에 모아 시간 기반 테스트를 쉽게 한다. */
    private Instant now() {
        return clock.instant();
    }

    /** 현재 시각보다 유효 시간이 지난 모든 설치 세션을 제거한다. */
    private void removeExpiredEntries() {
        Instant now = now();
        installSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record ExpiringInstallSession(DiscordBotInstallSession installSession, Instant expiresAt) {
    }
}
