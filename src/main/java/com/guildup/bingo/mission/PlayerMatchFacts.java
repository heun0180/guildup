package com.guildup.bingo.mission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 한 Match를 한 번 해석해 여러 빙고 규칙이 공유하는 플레이어 단위 사실이다. */
public record PlayerMatchFacts(
        String matchId, Instant playedAt, String mapName, String gameMode,
        Map<String, BigDecimal> metrics, List<KillFact> kills,
        Map<String, Integer> throwableUses, int clanMembersInMatch,
        Instant latestEvidenceAt
) {
    public PlayerMatchFacts {
        metrics = Map.copyOf(metrics); kills = List.copyOf(kills);
        throwableUses = Map.copyOf(throwableUses);
    }
    public BigDecimal metric(String key) { return metrics.getOrDefault(key, BigDecimal.ZERO); }
    public record KillFact(String weapon, String weaponCategory, String throwable,
                           double distance, boolean wallPenetration, Instant occurredAt) {}
}
