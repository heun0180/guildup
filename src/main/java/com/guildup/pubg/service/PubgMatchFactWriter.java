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
    public boolean saveMatchIfAbsent(String shard, PubgMatch source) {
        if (matches.findByMatchId(source.matchId()).isPresent()) return false;
        Instant now = clock.instant();
        PubgStoredMatch stored = new PubgStoredMatch(source.matchId(), shard, source.playedAt(), source.gameMode(),
                source.matchType(), source.mapName(), source.customMatch(), source.telemetryUrl(), source.durationSeconds(), now);
        int teamNumber = 0;
        for (PubgTeam team : source.teams()) {
            teamNumber++;
            for (PubgParticipant player : team.participants()) {
                if (player.accountId() == null) continue;
                stored.addPlayer(new PubgStoredMatchPlayer(stored, player.accountId(), player.name(), teamNumber,
                        player.kills(), decimal(player.damageDealt()), player.assists(), player.dbnos(),
                        player.headshotKills(), player.revives(), player.heals(), player.boosts(),
                        decimal(player.walkDistance()), decimal(player.rideDistance()), decimal(player.swimDistance()),
                        player.winPlace(), now));
            }
        }
        matches.saveAndFlush(stored);
        return true;
    }

    @Transactional
    public StoredCounts saveTelemetry(String matchId, Map<String, PlayerMatchFacts> facts) {
        PubgStoredMatch stored = matches.findForUpdateByMatchId(matchId).orElseThrow();
        if (stored.isTelemetryLoaded()) return new StoredCounts(0, 0);
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
                    kill.wallPenetration(), kill.occurredAt(), now)));
        });
        stored.replaceTelemetry(kills, now);
        matches.flush();
        return new StoredCounts(facts.size(), kills.size());
    }

    private BigDecimal decimal(double value) { return BigDecimal.valueOf(value); }
    public record StoredCounts(int players, int kills) {}
}
