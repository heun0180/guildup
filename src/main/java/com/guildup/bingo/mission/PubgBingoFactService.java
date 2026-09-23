package com.guildup.bingo.mission;

import tools.jackson.databind.JsonNode;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.PubgTelemetryClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 공식 Match 통계와 Telemetry event를 한 번 순회해 공통 PlayerMatchFacts로 변환한다. */
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
        return facts(match, communityAccounts, false);
    }

    /** 원본 복구처럼 Telemetry 누락을 0으로 대체할 수 없는 작업에서 사용한다. */
    public synchronized Map<String, PlayerMatchFacts> factsRequired(PubgMatch match, Set<String> communityAccounts) {
        return facts(match, communityAccounts, true);
    }

    private Map<String, PlayerMatchFacts> facts(PubgMatch match, Set<String> communityAccounts,
                                                 boolean telemetryRequired) {
        Instant now = clock.instant();
        if (telemetryRequired && (match.telemetryUrl() == null
                || !match.telemetryUrl().startsWith("https://telemetry-cdn.pubg.com/"))) {
            throw new IllegalStateException("Telemetry URL이 없습니다: " + match.matchId());
        }
        FactCacheKey cacheKey = new FactCacheKey(match.matchId(), communityAccounts.stream().sorted().toList());
        CachedFacts cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(now)
                && (!telemetryRequired || cached.telemetryLoaded)) return cached.byAccount;
        Map<String, MutableFacts> mutable = new LinkedHashMap<>();
        for (PubgTeam team : match.teams()) {
            Set<String> teamAccounts = team.participants().stream().map(PubgParticipant::accountId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (PubgParticipant player : team.participants()) {
                MutableFacts f = new MutableFacts(match, player, communityAccounts);
                f.clanMembersInTeam = (int) teamAccounts.stream()
                        .filter(id -> !Objects.equals(id, player.accountId()) && communityAccounts.contains(id)).count();
                mutable.put(player.accountId(), f);
            }
        }
        JsonNode events = telemetry.get(match.telemetryUrl());
        boolean telemetryLoaded = events != null && events.isArray();
        if (telemetryRequired && !telemetryLoaded)
            throw new IllegalStateException("Telemetry 원본이 없습니다: " + match.matchId());
        if (events != null && events.isArray()) events.forEach(event -> accept(event, mutable));
        Map<String, PlayerMatchFacts> result = new LinkedHashMap<>();
        mutable.forEach((account, facts) -> result.put(account, facts.freeze()));
        Map<String, PlayerMatchFacts> immutable = Map.copyOf(result);
        cache.put(cacheKey, new CachedFacts(immutable, telemetryLoaded, now.plus(CACHE_TTL)));
        return immutable;
    }

    private void accept(JsonNode event, Map<String, MutableFacts> facts) {
        String type = text(event, "_T"); Instant at = instant(text(event, "_D"));
        switch (type) {
            case "LogPlayerKill" -> acceptKill(event, facts, at, false);
            case "LogPlayerKillV2" -> { acceptKill(event, facts, at, true); acceptGameResult(event.path("victimGameResult"), facts); }
            case "LogPlayerRevive" -> increment(facts, account(event, "reviver"), "REVIVES", 1, at);
            case "LogCharacterCarry" -> {
                String state = text(event, "carryState");
                if (state == null || state.toLowerCase(Locale.ROOT).contains("start") || state.equalsIgnoreCase("carry"))
                    increment(facts, account(event, "character"), "CARRY", 1, at);
            }
            case "LogPlayerUseThrowable" -> {
                MutableFacts f = facts.get(account(event, "attacker")); String itemId = item(event, "weapon");
                if (f != null && itemId != null) { f.throwableUses.merge(itemId, 1, Integer::sum); f.evidence(at); }
            }
            case "LogItemPickup" -> acceptItem(event, facts, false, at);
            case "LogItemPickupFromCarepackage" -> acceptCarePackagePickup(event, facts, at);
            case "LogItemPickupFromLootbox" -> acceptLootboxPickup(event, facts, at);
            case "LogItemUse" -> acceptItem(event, facts, true, at);
            case "LogPlayerUseFlareGun" -> increment(facts, account(event, "attacker"), "FLARE_GUN_USED", 1, at);
            case "LogVaultStart" -> {
                String account = account(event, "character"); increment(facts, account, "VAULT_COUNT", 1, at);
                if (event.path("isLedgeGrab").asBoolean(false)) increment(facts, account, "LEDGE_GRAB_COUNT", 1, at);
            }
            case "LogWheelDestroy" -> increment(facts, account(event, "attacker"), "WHEEL_DESTROY_COUNT", 1, at);
            case "LogParachuteLanding" -> increment(facts, account(event, "character"), "PARACHUTE_DISTANCE", event.path("distance").asDouble(), at);
            case "LogVehicleDestroy" -> increment(facts, account(event, "attacker"), "VEHICLE_DESTROY_COUNT", 1, at);
            case "LogVehicleDamage" -> increment(facts, account(event, "attacker"), "VEHICLE_DAMAGE", event.path("damage").asDouble(), at);
            case "LogArmorDestroy" -> acceptArmorDestroy(event, facts, at);
            case "LogEmPickupLiftOff" -> event.path("riders").forEach(rider -> increment(facts, account(rider), "EMERGENCY_PICKUP_RIDE", 1, at));
            case "LogPlayerDestroyBreachableWall" -> increment(facts, account(event, "attacker"), "BREACHABLE_WALL_DESTROY_COUNT", 1, at);
            case "LogVehicleRide" -> acceptVehicleRide(event, facts, at);
            case "LogMatchEnd" -> event.path("gameResultOnFinished").path("results").forEach(result -> acceptGameResult(result, facts));
            default -> { }
        }
    }

    private void acceptKill(JsonNode event, Map<String, MutableFacts> facts, Instant at, boolean version2) {
        JsonNode killer = event.path("killer"), victim = event.path("victim");
        String killerAccount = account(killer), victimAccount = account(victim);
        if (killerAccount == null || Objects.equals(killerAccount, victimAccount)
                || event.path("isSuicide").asBoolean(false) || sameKnownTeam(killer, victim)
                || (version2 && containsAccount(event.path("teamKillers_AccountId"), killerAccount))) return;

        JsonNode info = version2 ? effectiveKillDamageInfo(event, killerAccount) : event;
        String rawWeapon = text(info, "damageCauserName");
        String weapon = Optional.ofNullable(BingoWeaponCatalog.canonicalName(rawWeapon)).orElse(rawWeapon);
        double distanceMeters = Math.max(0, info.path("distance").asDouble()) / 100.0d;
        boolean wall = info.path("isThroughPenetrableWall").asBoolean(false);
        String throwable = isThrowable(rawWeapon) ? rawWeapon : null;
        MutableFacts f = facts.get(killerAccount);
        if (f == null) return;
        String identity = killIdentity(event, killerAccount, victimAccount, at);
        if (f.killKeys.add(identity)) {
            f.kills.add(new PlayerMatchFacts.KillFact(
                    weapon, BingoWeaponCatalog.category(rawWeapon), throwable, distanceMeters, wall, at));
            f.evidence(at);
        }
    }

    /** 같은 사용자가 직접 마무리했다면 최종 피해 무기를, 아니면 PUBG kill credit의 무기를 사용한다. */
    private JsonNode effectiveKillDamageInfo(JsonNode event, String killerAccount) {
        JsonNode finishInfo = event.path("finishDamageInfo");
        if (Objects.equals(killerAccount, account(event, "finisher")) && hasDamageCauser(finishInfo)) return finishInfo;
        return event.path("killerDamageInfo");
    }

    private boolean hasDamageCauser(JsonNode info) {
        String value = text(info, "damageCauserName");
        return value != null && !value.isBlank();
    }

    private String killIdentity(JsonNode event, String killer, String victim, Instant at) {
        String attackId = scalar(event, "attackId"), dbnoId = scalar(event, "dBNOId");
        if (attackId != null || dbnoId != null || victim != null)
            return Objects.toString(attackId, "") + '|' + Objects.toString(dbnoId, "") + '|'
                    + killer + '|' + Objects.toString(victim, "");
        return Objects.toString(at, "") + '|' + killer + '|'
                + Objects.toString(text(event.path("killerDamageInfo"), "damageCauserName"), "");
    }

    private String scalar(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private boolean containsAccount(JsonNode accountIds, String accountId) {
        if (!accountIds.isArray()) return false;
        for (JsonNode value : accountIds) if (Objects.equals(accountId, value.asText())) return true;
        return false;
    }

    private void acceptItem(JsonNode event, Map<String, MutableFacts> facts, boolean used, Instant at) {
        MutableFacts f = facts.get(account(event, "character")); String itemId = item(event, "item");
        if (f == null || itemId == null) return;
        (used ? f.usedItems : f.pickedItems).merge(itemId, 1, Integer::sum); f.evidence(at);
    }

    private void acceptCarePackagePickup(JsonNode event, Map<String, MutableFacts> facts, Instant at) {
        MutableFacts f = facts.get(account(event, "character")); String itemId = item(event, "item");
        if (f == null || itemId == null) return;
        f.add("CARE_PACKAGE_PICKUP", 1); f.pickedItems.merge(itemId, 1, Integer::sum);
        f.carePackageItems.merge(itemId, 1, Integer::sum); f.evidence(at);
    }

    private void acceptLootboxPickup(JsonNode event, Map<String, MutableFacts> facts, Instant at) {
        JsonNode character = event.path("character"); MutableFacts f = facts.get(account(character));
        String itemId = item(event, "item"); if (f == null || itemId == null) return;
        f.pickedItems.merge(itemId, 1, Integer::sum);
        int pickerTeam = character.path("teamId").asInt(Integer.MIN_VALUE);
        int ownerTeam = event.path("ownerTeamId").asInt(Integer.MIN_VALUE);
        if (pickerTeam != Integer.MIN_VALUE && ownerTeam != Integer.MIN_VALUE && pickerTeam != ownerTeam) f.add("ENEMY_LOOTBOX_PICKUP", 1);
        f.evidence(at);
    }

    private void acceptArmorDestroy(JsonNode event, Map<String, MutableFacts> facts, Instant at) {
        JsonNode attacker = event.path("attacker"), victim = event.path("victim"); MutableFacts f = facts.get(account(attacker));
        if (f == null || !differentKnownTeam(attacker, victim)) return;
        f.add("ARMOR_DESTROY_COUNT", 1); String itemId = item(event, "item");
        if (itemId != null) f.destroyedArmor.merge(itemId, 1, Integer::sum); f.evidence(at);
    }

    private void acceptVehicleRide(JsonNode event, Map<String, MutableFacts> facts, Instant at) {
        String riderAccount = account(event, "character"); MutableFacts rider = facts.get(riderAccount); if (rider == null) return;
        long clanPassengers = java.util.stream.StreamSupport.stream(event.path("fellowPassengers").spliterator(), false)
                .map(this::account).filter(Objects::nonNull).filter(rider.communityAccounts::contains)
                .filter(id -> !Objects.equals(id, riderAccount)).count();
        rider.max("MAX_CLAN_VEHICLE_PASSENGERS", clanPassengers); rider.evidence(at);
    }

    private void acceptGameResult(JsonNode result, Map<String, MutableFacts> facts) {
        MutableFacts f = facts.get(text(result, "accountId"));
        if (f == null) return;
        JsonNode stats = result.path("stats");
        overrideDistance(stats, "distanceOnFoot", "WALK_DISTANCE", f);
        overrideDistance(stats, "distanceOnVehicle", "RIDE_DISTANCE", f);
        overrideDistance(stats, "distanceOnSwim", "SWIM_DISTANCE", f);
        overrideDistance(stats, "distanceOnFreefall", "FREEFALL_DISTANCE", f);
    }

    private void overrideDistance(JsonNode stats, String source, String target, MutableFacts facts) {
        if (stats.has(source) && stats.path(source).isNumber() && stats.path(source).asDouble() >= 0)
            facts.put(target, stats.path(source).asDouble());
    }

    private boolean differentKnownTeam(JsonNode first, JsonNode second) {
        int firstTeam = first.path("teamId").asInt(Integer.MIN_VALUE);
        int secondTeam = second.path("teamId").asInt(Integer.MIN_VALUE);
        return firstTeam != Integer.MIN_VALUE && secondTeam != Integer.MIN_VALUE && firstTeam != secondTeam;
    }
    private boolean sameKnownTeam(JsonNode first, JsonNode second) {
        int firstTeam = first.path("teamId").asInt(Integer.MIN_VALUE);
        int secondTeam = second.path("teamId").asInt(Integer.MIN_VALUE);
        return firstTeam != Integer.MIN_VALUE && secondTeam != Integer.MIN_VALUE && firstTeam == secondTeam;
    }
    private boolean isThrowable(String weapon) {
        String n = BingoWeaponCatalog.normalize(weapon); return n.contains("grenade") || n.contains("molotov");
    }
    private void increment(Map<String, MutableFacts> facts, String account, String key, double value, Instant at) {
        MutableFacts f = facts.get(account); if (f != null) {
            if ("REVIVES".equals(key) && f.telemetryOverrides.add(key)) f.put(key, 0);
            f.add(key, value); f.evidence(at);
        }
    }
    private String account(JsonNode event, String node) { return account(event.path(node)); }
    private String account(JsonNode character) {
        String id = text(character, "accountId"); return id == null ? text(character, "playerId") : id;
    }
    private String item(JsonNode event, String node) {
        JsonNode value = event.path(node); String id = text(value, "itemId");
        return BingoItemCatalog.canonicalId(id == null ? text(value, "damageCauserName") : id);
    }
    private String text(JsonNode node, String field) { JsonNode v = node.path(field); return v.isMissingNode() || v.isNull() ? null : v.asText(); }
    private Instant instant(String value) { try { return value == null ? null : Instant.parse(value); } catch (RuntimeException ignored) { return null; } }

    private static final class MutableFacts {
        final PubgMatch match; final Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        final List<PlayerMatchFacts.KillFact> kills = new ArrayList<>();
        final Map<String, Integer> throwableUses = new LinkedHashMap<>(), pickedItems = new LinkedHashMap<>();
        final Map<String, Integer> usedItems = new LinkedHashMap<>(), carePackageItems = new LinkedHashMap<>(), destroyedArmor = new LinkedHashMap<>();
        final Set<String> telemetryOverrides = new HashSet<>(), killKeys = new HashSet<>(), communityAccounts;
        int clanMembersInTeam; Instant latestEvidence;
        MutableFacts(PubgMatch match, PubgParticipant p, Set<String> communityAccounts) {
            this.match = match; this.communityAccounts = Set.copyOf(communityAccounts);
            put("KILLS", p.kills()); put("DAMAGE_DEALT", p.damageDealt()); put("ASSISTS", p.assists());
            put("DBNOS", p.dbnos()); put("HEADSHOT_KILLS", p.headshotKills()); put("ROAD_KILLS", p.roadKills());
            put("WINS", p.winPlace() == 1 ? 1 : 0); put("TOP10", p.winPlace() > 0 && p.winPlace() <= 10 ? 1 : 0);
            put("SURVIVAL_TIME", p.survivalTime()); put("MATCHES_PLAYED", 1); put("HEALS", p.heals());
            put("BOOSTS", p.boosts()); put("WALK_DISTANCE", p.walkDistance()); put("RIDE_DISTANCE", p.rideDistance());
            put("SWIM_DISTANCE", p.swimDistance()); put("REVIVES", p.revives()); latestEvidence = match.playedAt();
        }
        void put(String key, double value) { metrics.put(key, BigDecimal.valueOf(value)); }
        void add(String key, double value) { metrics.merge(key, BigDecimal.valueOf(value), BigDecimal::add); }
        void max(String key, double value) { metrics.merge(key, BigDecimal.valueOf(value), BigDecimal::max); }
        void evidence(Instant at) { if (at != null && (latestEvidence == null || at.isAfter(latestEvidence))) latestEvidence = at; }
        PlayerMatchFacts freeze() { return new PlayerMatchFacts(match.matchId(), match.playedAt(), match.mapName(), match.gameMode(),
                metrics, kills, throwableUses, pickedItems, usedItems, carePackageItems, destroyedArmor, clanMembersInTeam, latestEvidence); }
    }
    private record CachedFacts(Map<String, PlayerMatchFacts> byAccount, boolean telemetryLoaded, Instant expiresAt) {}
    private record FactCacheKey(String matchId, List<String> communityAccounts) {}
}
