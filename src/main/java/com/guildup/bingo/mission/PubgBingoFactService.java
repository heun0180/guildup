package com.guildup.bingo.mission;

import tools.jackson.databind.JsonNode;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.PubgTelemetryClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 공식 Match 통계와 Telemetry event를 공통 PlayerMatchFacts로 변환한다. */
@Service
public class PubgBingoFactService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final PubgTelemetryClient telemetry;
    private final Clock clock;
    private final Map<FactCacheKey, CachedFacts> cache = new LinkedHashMap<>();

    public PubgBingoFactService(PubgTelemetryClient telemetry, Clock clock) {
        this.telemetry = telemetry; this.clock = clock;
    }

    public synchronized Map<String, PlayerMatchFacts> facts(PubgMatch match, Set<String> communityAccounts) {
        Instant now = clock.instant();
        FactCacheKey cacheKey = new FactCacheKey(match.matchId(), communityAccounts.stream().sorted().toList());
        CachedFacts cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(now)) return cached.byAccount;
        Map<String, MutableFacts> mutable = new LinkedHashMap<>();
        Set<String> matchAccounts = match.teams().stream().flatMap(team -> team.participants().stream())
                .map(PubgParticipant::accountId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (PubgTeam team : match.teams()) for (PubgParticipant player : team.participants()) {
            MutableFacts f = new MutableFacts(match, player);
            long clanmates = matchAccounts.stream()
                    .filter(id -> !Objects.equals(id, player.accountId()) && communityAccounts.contains(id)).count();
            f.clanMembersInMatch = (int) clanmates;
            mutable.put(player.accountId(), f);
        }
        JsonNode events = telemetry.get(match.telemetryUrl());
        if (events != null && events.isArray()) events.forEach(event -> accept(event, mutable));
        Map<String, PlayerMatchFacts> result = new LinkedHashMap<>();
        mutable.forEach((account, facts) -> result.put(account, facts.freeze()));
        Map<String, PlayerMatchFacts> immutable = Map.copyOf(result);
        cache.put(cacheKey, new CachedFacts(immutable, now.plus(CACHE_TTL)));
        return immutable;
    }

    private void accept(JsonNode event, Map<String, MutableFacts> facts) {
        String type = text(event, "_T"); Instant at = instant(text(event, "_D"));
        switch (type) {
            case "LogPlayerKill", "LogPlayerKillV2" -> acceptKill(event, facts, at);
            case "LogPlayerRevive" -> increment(facts, account(event, "reviver"), "REVIVES", 1, at);
            case "LogCharacterCarry" -> {
                String state = text(event, "carryState");
                if (state == null || state.toLowerCase(Locale.ROOT).contains("start") || state.equalsIgnoreCase("carry"))
                    increment(facts, account(event, "character"), "CARRY", 1, at);
            }
            case "LogPlayerUseThrowable" -> {
                String account = account(event, "attacker"); String item = item(event, "weapon");
                MutableFacts f = facts.get(account); if (f != null) { f.throwableUses.merge(item, 1, Integer::sum); f.evidence(at); }
            }
            case "LogItemPickupFromCarepackage" -> increment(facts, account(event, "character"), "CARE_PACKAGE_PICKUP", 1, at);
            case "LogPlayerUseFlareGun" -> increment(facts, account(event, "attacker"), "FLARE_GUN_USED", 1, at);
            case "LogVaultStart" -> {
                String account = account(event, "character"); increment(facts, account, "VAULT_COUNT", 1, at);
                if (event.path("isLedgeGrab").asBoolean(false)) increment(facts, account, "LEDGE_GRAB_COUNT", 1, at);
            }
            case "LogWheelDestroy" -> increment(facts, account(event, "attacker"), "WHEEL_DESTROY_COUNT", 1, at);
            case "LogParachuteLanding" -> increment(facts, account(event, "character"), "PARACHUTE_DISTANCE", event.path("distance").asDouble(), at);
            default -> { }
        }
    }

    private void acceptKill(JsonNode event, Map<String, MutableFacts> facts, Instant at) {
        String account = account(event, "killer"); JsonNode info = event.path("killerDamageInfo");
        String weapon = text(event, "damageCauserName");
        if (weapon == null) weapon = text(info, "damageCauserName");
        double distance = event.has("distance") ? event.path("distance").asDouble() : info.path("distance").asDouble();
        boolean wall = event.path("isThroughPenetrableWall").asBoolean(info.path("isThroughPenetrableWall").asBoolean(false));
        String throwable = isThrowable(weapon) ? weapon : null;
        MutableFacts f = facts.get(account);
        if (f != null) { f.kills.add(new PlayerMatchFacts.KillFact(weapon, BingoWeaponCatalog.category(weapon), throwable, distance, wall, at)); f.evidence(at); }
    }

    private boolean isThrowable(String weapon) {
        String n = BingoWeaponCatalog.normalize(weapon);
        return n.contains("grenade") || n.contains("molotov");
    }
    private void increment(Map<String, MutableFacts> facts, String account, String key, double value, Instant at) {
        MutableFacts f = facts.get(account); if (f != null) {
            if ("REVIVES".equals(key) && f.telemetryOverrides.add(key)) f.put(key, 0);
            f.add(key, value); f.evidence(at);
        }
    }
    private String account(JsonNode event, String node) {
        JsonNode value = event.path(node); String id = text(value, "accountId");
        return id == null ? text(value, "playerId") : id;
    }
    private String item(JsonNode event, String node) {
        JsonNode value = event.path(node); String id = text(value, "itemId");
        return id == null ? text(value, "damageCauserName") : id;
    }
    private String text(JsonNode node, String field) { JsonNode v = node.path(field); return v.isMissingNode() || v.isNull() ? null : v.asText(); }
    private Instant instant(String value) { try { return value == null ? null : Instant.parse(value); } catch (RuntimeException ignored) { return null; } }

    private static final class MutableFacts {
        final PubgMatch match; final Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        final List<PlayerMatchFacts.KillFact> kills = new ArrayList<>();
        final Map<String, Integer> throwableUses = new LinkedHashMap<>(); final Set<String> telemetryOverrides = new HashSet<>();
        int clanMembersInMatch; Instant latestEvidence;
        MutableFacts(PubgMatch match, PubgParticipant p) {
            this.match = match;
            put("KILLS", p.kills()); put("DAMAGE_DEALT", p.damageDealt()); put("ASSISTS", p.assists());
            put("DBNOS", p.dbnos()); put("HEADSHOT_KILLS", p.headshotKills()); put("ROAD_KILLS", p.roadKills());
            put("WINS", p.winPlace() == 1 ? 1 : 0); put("TOP10", p.winPlace() > 0 && p.winPlace() <= 10 ? 1 : 0);
            put("SURVIVAL_TIME", p.survivalTime()); put("MATCHES_PLAYED", 1); put("HEALS", p.heals());
            put("BOOSTS", p.boosts()); put("WALK_DISTANCE", p.walkDistance()); put("RIDE_DISTANCE", p.rideDistance());
            put("SWIM_DISTANCE", p.swimDistance()); put("REVIVES", p.revives());
            latestEvidence = match.playedAt();
        }
        void put(String key, double value) { metrics.put(key, BigDecimal.valueOf(value)); }
        void add(String key, double value) { metrics.merge(key, BigDecimal.valueOf(value), BigDecimal::add); }
        void evidence(Instant at) { if (at != null && (latestEvidence == null || at.isAfter(latestEvidence))) latestEvidence = at; }
        PlayerMatchFacts freeze() { return new PlayerMatchFacts(match.matchId(), match.playedAt(), match.mapName(), match.gameMode(), metrics, kills, throwableUses, clanMembersInMatch, latestEvidence); }
    }
    private record CachedFacts(Map<String, PlayerMatchFacts> byAccount, Instant expiresAt) {}
    private record FactCacheKey(String matchId, List<String> communityAccounts) {}
}
