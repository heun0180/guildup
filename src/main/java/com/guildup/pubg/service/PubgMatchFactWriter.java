package com.guildup.pubg.service;

import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.pubg.domain.PubgStoredMatch;
import com.guildup.pubg.domain.PubgStoredMatchKill;
import com.guildup.pubg.domain.PubgStoredMatchPlayer;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgTeam;
import com.guildup.pubg.repository.PubgStoredMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guildup.pubg.model.PubgPlatform;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 외부 호출이 끝난 결과만 짧은 독립 transaction으로 저장한다. */
@Service
public class PubgMatchFactWriter {
    private final PubgStoredMatchRepository matches;
    private final Clock clock;

    public PubgMatchFactWriter(PubgStoredMatchRepository matches, Clock clock) {
        this.matches = matches; this.clock = clock;
    }

    @Transactional
    public boolean saveMatchIfAbsent(PubgPlatform platform, PubgMatch source) {
        if (matches.findByShardAndMatchId(platform.getShard(), source.matchId()).isPresent()) return false;
        Instant now = clock.instant();
        PubgStoredMatch stored = new PubgStoredMatch(source.matchId(), platform.getShard(), source.playedAt(), source.gameMode(),
                source.matchType(), source.mapName(), source.customMatch(), source.telemetryUrl(), source.durationSeconds(), now);
        int teamNumber = 0;
        for (PubgTeam team : source.teams()) {
            teamNumber++;
            for (PubgParticipant player : team.participants()) {
                if (player.accountId() == null) continue;
                PubgStoredMatchPlayer storedPlayer = new PubgStoredMatchPlayer(stored, player.accountId(), player.name(), teamNumber,
                        player.kills(), decimal(player.damageDealt()), player.assists(), player.dbnos(),
                        player.headshotKills(), player.revives(), player.heals(), player.boosts(),
                        decimal(player.walkDistance()), decimal(player.rideDistance()), decimal(player.swimDistance()),
                        player.winPlace(), now);
                storedPlayer.originalCombatStats(player.survivalTime(), player.roadKills());
                stored.addPlayer(storedPlayer);
            }
        }
        matches.saveAndFlush(stored);
        return true;
    }

    @Transactional
    public StoredCounts saveTelemetry(PubgPlatform platform, String matchId, Map<String, PlayerMatchFacts> facts) {
        PubgStoredMatch stored = matches.findForUpdateByShardAndMatchId(platform.getShard(), matchId).orElseThrow(() ->
                new IllegalStateException("PUBG source match missing during telemetry fact save: matchId=" + matchId));
        if (!stored.needsTelemetryFactUpgrade()) return new StoredCounts(0, 0);
        Instant now = clock.instant();
        Map<String, PubgStoredMatchPlayer> players = stored.getPlayers().stream().collect(
                java.util.stream.Collectors.toMap(PubgStoredMatchPlayer::getAccountId, value -> value));
        List<PubgStoredMatchKill> kills = new ArrayList<>();
        facts.forEach((accountId, value) -> {
            PubgStoredMatchPlayer player = players.get(accountId);
            if (player == null) return;
            player.telemetryFacts(PubgFactCodec.decimals(value.metrics()),
                    PubgFactCodec.integers(value.throwableUses()), PubgFactCodec.integers(value.pickedItems()),
                    PubgFactCodec.integers(value.usedItems()), PubgFactCodec.integers(value.carePackageItems()),
                    PubgFactCodec.integers(value.destroyedArmor()), value.clanMembersInTeam(), value.latestEvidenceAt(), now);
            value.kills().forEach(kill -> kills.add(new PubgStoredMatchKill(stored, accountId,
                    kill.victimAccountId(), kill.weapon(), kill.weaponCategory(), kill.throwable(), kill.distance(),
                    kill.wallPenetration(), kill.headshot(), kill.occurredAt(), now)));
        });
        stored.replaceTelemetry(kills, now);
        matches.flush();
        return new StoredCounts(facts.size(), kills.size());
    }

    private BigDecimal decimal(double value) { return BigDecimal.valueOf(value); }
    public record StoredCounts(int players, int kills) {}
}
