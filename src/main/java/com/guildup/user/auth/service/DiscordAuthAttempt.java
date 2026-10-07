package com.guildup.user.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 목적과 연결할 User는 요청 파라미터가 아니라 세션에 보관한다. */
public record DiscordAuthAttempt(String state, String redirectUri, Purpose purpose, Long userId,
                                 Instant createdAt) implements Serializable {
    public static final String ATTRIBUTE = "DISCORD_AUTH_ATTEMPT";
    public enum Purpose { LOGIN, LINK_ACCOUNT, WITHDRAWAL }

    public boolean matches(String received) {
        return received != null && received.length() == state.length()
                && !createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(10)))
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    @Override public String toString() { return "DiscordAuthAttempt[redacted]"; }
}
