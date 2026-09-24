package com.guildup.pubg.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 한 Match를 한 번 해석해 여러 GuildUp 콘텐츠가 공유하는 플레이어 단위 사실이다. */
public record PlayerMatchFacts(
        String matchId, Instant playedAt, String mapName, String gameMode,
        Map<String, BigDecimal> metrics, List<KillFact> kills,
        Map<String, Integer> throwableUses, Map<String, Integer> pickedItems,
        Map<String, Integer> usedItems, Map<String, Integer> carePackageItems,
        Map<String, Integer> destroyedArmor, int clanMembersInTeam,
        Instant latestEvidenceAt
) {
    public PlayerMatchFacts {
        metrics = Map.copyOf(metrics); kills = List.copyOf(kills);
        throwableUses = Map.copyOf(throwableUses);
        pickedItems = Map.copyOf(pickedItems); usedItems = Map.copyOf(usedItems);
        carePackageItems = Map.copyOf(carePackageItems); destroyedArmor = Map.copyOf(destroyedArmor);
    }
    public PlayerMatchFacts(String matchId, Instant playedAt, String mapName, String gameMode,
            Map<String, BigDecimal> metrics, List<KillFact> kills, Map<String, Integer> throwableUses,
            int clanMembersInTeam, Instant latestEvidenceAt) {
        this(matchId, playedAt, mapName, gameMode, metrics, kills, throwableUses,
                Map.of(), Map.of(), Map.of(), Map.of(), clanMembersInTeam, latestEvidenceAt);
    }
    public BigDecimal metric(String key) { return metrics.getOrDefault(key, BigDecimal.ZERO); }
    public record KillFact(String victimAccountId, String weapon, String weaponCategory, String throwable,
                           double distance, boolean wallPenetration, Instant occurredAt) {
        public KillFact(String weapon, String weaponCategory, String throwable,
                        double distance, boolean wallPenetration, Instant occurredAt) {
            this(null, weapon, weaponCategory, throwable, distance, wallPenetration, occurredAt);
        }
    }
}
