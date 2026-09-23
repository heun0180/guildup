package com.guildup.bingo;

import com.guildup.bingo.mission.*;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.PubgTelemetryClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PubgBingoFactServiceTests {
    @Test
    void convertsOfficialTelemetryEventsToReusableFactsAndCachesPerMatch() throws Exception {
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        String json = """
                [
                 {"_T":"LogPlayerKill","_D":"2026-09-22T12:03:00Z","killer":{"accountId":"a"},"damageCauserName":"M416","distance":310,"isThroughPenetrableWall":true},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:04:00Z","killer":{"accountId":"a"},"killerDamageInfo":{"damageCauserName":"Grenade","distance":20}},
                 {"_T":"LogPlayerRevive","_D":"2026-09-22T12:05:00Z","reviver":{"accountId":"a"}},
                 {"_T":"LogCharacterCarry","_D":"2026-09-22T12:06:00Z","character":{"accountId":"a"},"carryState":"start"},
                 {"_T":"LogPlayerUseThrowable","_D":"2026-09-22T12:07:00Z","attacker":{"accountId":"a"},"weapon":{"itemId":"SmokeBomb"}},
                 {"_T":"LogItemPickupFromCarepackage","_D":"2026-09-22T12:08:00Z","character":{"accountId":"a"},"item":{"itemId":"Item_Weapon_AWM_C"}},
                 {"_T":"LogPlayerUseFlareGun","_D":"2026-09-22T12:09:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogVaultStart","_D":"2026-09-22T12:10:00Z","character":{"accountId":"a"},"isLedgeGrab":true},
                 {"_T":"LogWheelDestroy","_D":"2026-09-22T12:11:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogParachuteLanding","_D":"2026-09-22T12:12:00Z","character":{"accountId":"a"},"distance":2000},
                 {"_T":"LogVehicleDestroy","_D":"2026-09-22T12:13:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogVehicleDamage","_D":"2026-09-22T12:14:00Z","attacker":{"accountId":"a"},"damage":325.5},
                 {"_T":"LogArmorDestroy","_D":"2026-09-22T12:15:00Z","attacker":{"accountId":"a","teamId":1},"victim":{"accountId":"c","teamId":2},"item":{"itemId":"Item_Head_G_01_Lv3_C"}},
                 {"_T":"LogItemPickup","_D":"2026-09-22T12:16:00Z","character":{"accountId":"a"},"item":{"itemId":"Item_Heal_FirstAid_C"}},
                 {"_T":"LogItemUse","_D":"2026-09-22T12:17:00Z","character":{"accountId":"a"},"item":{"itemId":"Item_Heal_FirstAid_C"}},
                 {"_T":"LogItemPickupFromLootbox","_D":"2026-09-22T12:18:00Z","character":{"accountId":"a","teamId":1},"item":{"itemId":"Item_Weapon_AWM_C"},"ownerTeamId":2,"creatorAccountId":"c"},
                 {"_T":"LogEmPickupLiftOff","_D":"2026-09-22T12:19:00Z","riders":[{"accountId":"a"}]},
                 {"_T":"LogPlayerDestroyBreachableWall","_D":"2026-09-22T12:20:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogVehicleRide","_D":"2026-09-22T12:21:00Z","character":{"accountId":"a"},"fellowPassengers":[{"accountId":"b"},{"accountId":"c"}]},
                 {"_T":"LogMatchEnd","_D":"2026-09-22T12:22:00Z","gameResultOnFinished":{"results":[{"accountId":"a","stats":{"distanceOnFreefall":1550}}]}}
                ]""";
        when(telemetry.get("https://telemetry-cdn.pubg.com/test.json")).thenReturn(JsonMapper.builder().build().readTree(json));
        PubgBingoFactService service = new PubgBingoFactService(telemetry, Clock.fixed(Instant.parse("2026-09-22T13:00:00Z"), ZoneOffset.UTC));
        PubgParticipant player = new PubgParticipant("a","Apple",2,1000,3,4,1,5,6,2,1,1,1200,5000,10000,500,310);
        PubgMatch match = new PubgMatch("m",Instant.parse("2026-09-22T12:00:00Z"),"squad","Erangel_Main",
                "competitive",false,"https://telemetry-cdn.pubg.com/test.json",
                List.of(new PubgTeam(List.of(player,new PubgParticipant("b","Fox")))));

        PlayerMatchFacts facts = service.facts(match,Set.of("a","b")).get("a");
        service.facts(match,Set.of("a","b"));

        assertThat(facts.kills()).hasSize(2);
        assertThat(facts.metric("REVIVES")).isEqualByComparingTo("1");
        assertThat(facts.metric("CARRY")).isEqualByComparingTo("1");
        assertThat(facts.metric("CARE_PACKAGE_PICKUP")).isEqualByComparingTo("1");
        assertThat(facts.metric("FLARE_GUN_USED")).isEqualByComparingTo("1");
        assertThat(facts.metric("VAULT_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("LEDGE_GRAB_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("WHEEL_DESTROY_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("PARACHUTE_DISTANCE")).isEqualByComparingTo("2000");
        assertThat(facts.metric("VEHICLE_DESTROY_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("VEHICLE_DAMAGE")).isEqualByComparingTo("325.5");
        assertThat(facts.metric("ARMOR_DESTROY_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("FREEFALL_DISTANCE")).isEqualByComparingTo("1550");
        assertThat(facts.metric("ENEMY_LOOTBOX_PICKUP")).isEqualByComparingTo("1");
        assertThat(facts.metric("EMERGENCY_PICKUP_RIDE")).isEqualByComparingTo("1");
        assertThat(facts.metric("BREACHABLE_WALL_DESTROY_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("MAX_CLAN_VEHICLE_PASSENGERS")).isEqualByComparingTo("1");
        assertThat(facts.pickedItems()).containsEntry("Item_Heal_FirstAid_C",1).containsEntry("Item_Weapon_AWM_C",2);
        assertThat(facts.usedItems()).containsEntry("Item_Heal_FirstAid_C",1);
        assertThat(facts.carePackageItems()).containsEntry("Item_Weapon_AWM_C",1);
        assertThat(facts.destroyedArmor()).containsEntry("Item_Head_G_01_Lv3_C",1);
        assertThat(facts.throwableUses()).containsEntry("SmokeBomb",1);
        assertThat(facts.clanMembersInTeam()).isEqualTo(1);
        verify(telemetry, times(1)).get(anyString());
    }

    @Test
    void countsOnlyCommunityMembersOnThePlayersTeam() {
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        PubgBingoFactService service = new PubgBingoFactService(
                telemetry, Clock.fixed(Instant.parse("2026-09-22T13:00:00Z"), ZoneOffset.UTC));
        PubgMatch match = new PubgMatch("m", Instant.parse("2026-09-22T12:00:00Z"), "squad", "Erangel_Main",
                null, List.of(
                new PubgTeam(List.of(new PubgParticipant("a", "Apple"), new PubgParticipant("b", "Fox"))),
                new PubgTeam(List.of(new PubgParticipant("c", "Bear")))));

        Map<String, PlayerMatchFacts> facts = service.facts(match, Set.of("a", "b", "c"));

        assertThat(facts.get("a").clanMembersInTeam()).isEqualTo(1);
        assertThat(facts.get("b").clanMembersInTeam()).isEqualTo(1);
        assertThat(facts.get("c").clanMembersInTeam()).isZero();
    }

    @Test
    void convertsKillCentimetersToMetersAtTheBoundaryAndDeduplicatesKills() throws Exception {
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        String json = """
                [
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:01:00Z","attackId":1,"dBNOId":11,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v1","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":19999}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:02:00Z","attackId":2,"dBNOId":12,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v2","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":20000}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:02:00Z","attackId":2,"dBNOId":12,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v2","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":20000}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:03:00Z","attackId":3,"dBNOId":13,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v3","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":20001}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:04:00Z","attackId":4,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"ally","teamId":1},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":30000}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:05:00Z","attackId":5,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"a","teamId":1},"isSuicide":true,"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":30000}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:06:00Z","attackId":6,"killer":{"accountId":"a"},"victim":{"accountId":"ally-unknown-team"},"teamKillers_AccountId":["a"],"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":30000}}
                ]""";
        when(telemetry.get(anyString())).thenReturn(JsonMapper.builder().build().readTree(json));
        PubgBingoFactService service = new PubgBingoFactService(telemetry, Clock.systemUTC());
        PubgMatch match = new PubgMatch("m", Instant.parse("2026-09-22T12:00:00Z"), "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/test.json",
                List.of(new PubgTeam(List.of(new PubgParticipant("a", "Apple")))));

        PlayerMatchFacts facts = service.facts(match, Set.of("a")).get("a");

        assertThat(facts.kills()).extracting(PlayerMatchFacts.KillFact::distance)
                .containsExactly(199.99, 200.0, 200.01);
        BingoMissionEngine engine = new BingoMissionEngine();
        assertThat(engine.value(com.guildup.bingo.domain.BingoMissionType.LONG_DISTANCE_KILL,
                facts, Map.of("distance", 200))).isEqualByComparingTo("2");
        assertThat(engine.value(com.guildup.bingo.domain.BingoMissionType.WEAPON_KILLS,
                facts, Map.of("weapon", " VSS"))).isEqualByComparingTo("3");
    }

    @Test
    void usesFinishWeaponOnlyWhenTheCreditedKillerAlsoFinishedAndUsesGameResultDistances() throws Exception {
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        String json = """
                [
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:01:00Z","attackId":1,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v1","teamId":2},"finisher":{"accountId":"a"},"killerDamageInfo":{"damageCauserName":"WeapHK416_C","distance":30000},"finishDamageInfo":{"damageCauserName":"WeapVSS_C","distance":25000},"victimGameResult":{"accountId":"v1","stats":{"distanceOnFoot":10}}},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:02:00Z","attackId":2,"killer":{"accountId":"a","teamId":1},"victim":{"accountId":"v2","teamId":2},"finisher":{"accountId":"b"},"killerDamageInfo":{"damageCauserName":"WeapVSS_C","distance":21000},"finishDamageInfo":{"damageCauserName":"WeapHK416_C","distance":100}},
                 {"_T":"LogMatchEnd","_D":"2026-09-22T12:30:00Z","gameResultOnFinished":{"results":[{"accountId":"a","stats":{"distanceOnFoot":29999,"distanceOnVehicle":30000,"distanceOnSwim":12}}]}}
                ]""";
        when(telemetry.get(anyString())).thenReturn(JsonMapper.builder().build().readTree(json));
        PubgBingoFactService service = new PubgBingoFactService(telemetry, Clock.systemUTC());
        PubgParticipant participant = new PubgParticipant("a", "Apple", 2, 0, 0, 0, 0,
                0, 0, 0, 0, 1, 0, 1, 2, 3, 0);
        PubgMatch match = new PubgMatch("m", Instant.parse("2026-09-22T12:00:00Z"), "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/test.json",
                List.of(new PubgTeam(List.of(participant))));

        PlayerMatchFacts facts = service.facts(match, Set.of("a")).get("a");

        assertThat(facts.kills()).extracting(PlayerMatchFacts.KillFact::weapon).containsExactly("VSS", "VSS");
        assertThat(facts.metric("WALK_DISTANCE")).isEqualByComparingTo("29999");
        assertThat(facts.metric("RIDE_DISTANCE")).isEqualByComparingTo("30000");
        assertThat(facts.metric("SWIM_DISTANCE")).isEqualByComparingTo("12");
    }
}
