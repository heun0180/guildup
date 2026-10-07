package com.guildup.user.auth.service;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/** 세션에만 보관하는 단기 본인 확인 증거. 비밀번호/토큰/외부 식별자 없음. */
public record WithdrawalVerification(Long userId, Method method, Long loginMethodId,
                                     Instant verifiedAt) implements Serializable {
    public static final String ATTRIBUTE = "ACCOUNT_WITHDRAWAL_VERIFICATION";
    public enum Method { PASSWORD, DISCORD }

    public boolean validFor(Long id, Method expected, Long methodId, Instant now) {
        return userId.equals(id) && method == expected && loginMethodId.equals(methodId)
                && !verifiedAt.isAfter(now) && verifiedAt.isAfter(now.minus(Duration.ofMinutes(5)));
    }
}
