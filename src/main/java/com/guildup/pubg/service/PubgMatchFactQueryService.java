package com.guildup.pubg.service;

import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.pubg.domain.PubgStoredMatch;
import com.guildup.pubg.domain.PubgStoredMatchPlayer;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgTeam;
import com.guildup.pubg.model.PubgCommunityMetricSupport;
import com.guildup.pubg.repository.PubgStoredMatchRepository;
import com.guildup.pubg.repository.PubgStoredMatchKillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PubgMatchFactQueryService {
    private final PubgStoredMatchRepository matches;
    private final PubgStoredMatchKillRepository kills;
    public PubgMatchFactQueryService(PubgStoredMatchRepository matches, PubgStoredMatchKillRepository kills) {
        this.matches = matches; this.kills = kills;
    }

    @Transactional(readOnly = true)
    public Set<String> existingMatchIds(Collection<String> matchIds) {
        if (matchIds.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        List<String> ids = List.copyOf(matchIds);
        for (int start = 0; start < ids.size(); start += 500) {
            result.addAll(matches.findExistingMatchIds(ids.subList(start, Math.min(start + 500, ids.size()))));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<StoredMatchFacts> findBetween(Instant from, Instant to, Set<String> communityAccounts) {
        List<PubgStoredMatch> stored = matches.findWithPlayersBetween(from, to);
        Map<Long, List<com.guildup.pubg.domain.PubgStoredMatchKill>> killsByMatch = stored.isEmpty() ? Map.of()
                : kills.findByMatchIdIn(stored.stream().map(PubgStoredMatch::getId).toList()).stream()
                .collect(Collectors.groupingBy(kill -> kill.getMatch().getId()));
        return stored.stream().map(match -> toFacts(match, communityAccounts,
                killsByMatch.getOrDefault(match.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public List<PubgMatch> findTelemetryMissing(Collection<String> matchIds) {
        if (matchIds.isEmpty()) return List.of();
        List<PubgMatch> result = new ArrayList<>();
        List<String> ids = List.copyOf(matchIds);
        for (int start = 0; start < ids.size(); start += 500) {
            matches.findTelemetryMissing(ids.subList(start, Math.min(start + 500, ids.size())))
                    .forEach(match -> result.add(toMatch(match)));
        }
        return result;
    }

    private StoredMatchFacts toFacts(PubgStoredMatch match, Set<String> communityAccounts,
                                     List<com.guildup.pubg.domain.PubgStoredMatchKill> storedKills) {
        Map<Integer, Set<String>> accountsByTeam = match.getPlayers().stream().collect(Collectors.groupingBy(
                PubgStoredMatchPlayer::getTeamNumber, Collectors.mapping(PubgStoredMatchPlayer::getAccountId, Collectors.toSet())));
        Map<String, PlayerMatchFacts> facts = new LinkedHashMap<>();
        for (PubgStoredMatchPlayer player : match.getPlayers()) {
            int clanMembers = (int) accountsByTeam.getOrDefault(player.getTeamNumber(), Set.of()).stream()
                    .filter(id -> !id.equals(player.getAccountId()) && communityAccounts.contains(id)).count();
            if (player.getTelemetryClanMembers() != null) clanMembers = Math.max(clanMembers, player.getTelemetryClanMembers());
            List<PlayerMatchFacts.KillFact> kills = storedKills.stream()
                    .filter(kill -> player.getAccountId().equals(kill.getKillerAccountId()))
                    .map(kill -> new PlayerMatchFacts.KillFact(kill.getVictimAccountId(), kill.getWeapon(),
                            kill.getWeaponCategory(), kill.getThrowable(), kill.getDistanceMeters().doubleValue(),
                            kill.isWallPenetration(), kill.getOccurredAt())).toList();
            Map<String, java.math.BigDecimal> metrics = PubgCommunityMetricSupport.forCommunity(
                    PubgFactCodec.decimals(player.getMetricsJson()), communityAccounts);
            facts.put(player.getAccountId(), new PlayerMatchFacts(match.getMatchId(), match.getStartedAt(),
                    match.getMapName(), match.getGameMode(), metrics, kills,
                    PubgFactCodec.integers(player.getThrowableUsesJson()), PubgFactCodec.integers(player.getPickedItemsJson()),
                    PubgFactCodec.integers(player.getUsedItemsJson()), PubgFactCodec.integers(player.getCarePackageItemsJson()),
                    PubgFactCodec.integers(player.getDestroyedArmorJson()), clanMembers, player.getLatestEvidenceAt()));
        }
        return new StoredMatchFacts(match.getMatchId(), match.getStartedAt(), match.getGameMode(), match.getMapName(),
                match.getMatchType(), match.getCustomMatch(), match.isTelemetryLoaded(), Map.copyOf(facts));
    }

    private PubgMatch toMatch(PubgStoredMatch match) {
        Map<Integer, List<PubgParticipant>> teams = new LinkedHashMap<>();
        match.getPlayers().forEach(player -> teams.computeIfAbsent(player.getTeamNumber(), ignored -> new ArrayList<>()).add(
                new PubgParticipant(player.getAccountId(), player.getPlayerName(), player.getKills(),
                        player.getDamage().doubleValue(), player.getAssists(), player.getDbnos(), player.getHeadshotKills(),
                        player.getHeals(), player.getBoosts(), player.getRevives(), 0, player.getPlacement(), 0,
                        player.getWalkDistance().doubleValue(), player.getRideDistance().doubleValue(),
                        player.getSwimDistance().doubleValue(), 0)));
        return new PubgMatch(match.getMatchId(), match.getStartedAt(), match.getGameMode(), match.getMapName(),
                match.getMatchType(), match.getCustomMatch(), match.getTelemetryUrl(),
                match.getDuration(), teams.values().stream().map(PubgTeam::new).toList());
    }

    public record StoredMatchFacts(String matchId, Instant startedAt, String gameMode, String mapName,
                                   String matchType, Boolean customMatch, boolean telemetryLoaded,
                                   Map<String, PlayerMatchFacts> byAccount) {}
}
