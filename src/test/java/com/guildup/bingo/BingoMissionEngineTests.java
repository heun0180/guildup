package com.guildup.bingo;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.mission.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class BingoMissionEngineTests {
    BingoMissionEngine engine = new BingoMissionEngine();
    Instant playedAt = Instant.parse("2026-09-22T12:00:00Z");

    @Test
    void keepsAllFortyExistingMissionNamesAndAddsKillBetWinAsGuildUpContent() {
        assertThat(BingoMissionType.values()).hasSize(41);
        assertThat(BingoMissionType.values()).contains(BingoMissionType.KILLS, BingoMissionType.WHEEL_DESTROY_COUNT,
                BingoMissionType.VEHICLE_DESTROY_COUNT, BingoMissionType.RIDE_WITH_CLAN_MEMBERS,
                BingoMissionType.KILL_BET_WIN);
        assertThat(BingoMissionType.KILLS.source()).isEqualTo(BingoMissionSource.PUBG_MATCH);
        assertThat(BingoMissionType.KILL_BET_WIN.source()).isEqualTo(BingoMissionSource.GUILDUP_CONTENT);
    }

    @Test
    void eventTotalSingleMatchAndOccurrencesUseOneRuleModel() {
        PlayerMatchFacts first = facts(Map.of("KILLS", bd(4)));
        BingoProgress total = progress(cell(BingoMissionType.KILLS, BingoAggregationType.EVENT_TOTAL, 7, null, null));
        apply(total, first); apply(total, facts(Map.of("KILLS", bd(3))));
        assertThat(total.isCompleted()).isTrue(); assertThat(total.getCurrentValue()).isEqualByComparingTo("7");

        BingoProgress single = progress(cell(BingoMissionType.KILLS, BingoAggregationType.SINGLE_MATCH, 5, null, null));
        apply(single, first); apply(single, facts(Map.of("KILLS", bd(6))));
        assertThat(single.isCompleted()).isTrue(); assertThat(single.getCurrentValue()).isEqualByComparingTo("6");

        BingoProgress occurrences = progress(cell(BingoMissionType.KILLS, BingoAggregationType.MATCH_OCCURRENCES, 5, 2, null));
        apply(occurrences, facts(Map.of("KILLS", bd(5)))); apply(occurrences, first); apply(occurrences, facts(Map.of("KILLS", bd(8))));
        assertThat(occurrences.getOccurrenceCount()).isEqualTo(2); assertThat(occurrences.isCompleted()).isTrue();
    }

    @Test
    void telemetryMissionFactsCoverWeaponsThrowableCarryVaultCarePackageAndWheel() {
        List<PlayerMatchFacts.KillFact> kills = List.of(
                new PlayerMatchFacts.KillFact("M416", "AR", null, 310, true, playedAt),
                new PlayerMatchFacts.KillFact("Grenade", null, "Grenade", 20, false, playedAt));
        PlayerMatchFacts facts = new PlayerMatchFacts("m", playedAt, "Erangel_Main", "squad",
                Map.of("REVIVES",bd(2),"CARRY",bd(1),"VAULT_COUNT",bd(4),"LEDGE_GRAB_COUNT",bd(1),
                        "CARE_PACKAGE_PICKUP",bd(1),"WHEEL_DESTROY_COUNT",bd(2)), kills,
                Map.of("SmokeBomb",3), 3, playedAt);
        assertValue(facts, BingoMissionType.WEAPON_KILLS, Map.of("weapon","M416"), 1);
        assertValue(facts, BingoMissionType.WEAPON_CATEGORY_KILLS, Map.of("weaponCategory","AR"), 1);
        assertValue(facts, BingoMissionType.THROWABLE_KILLS, Map.of("throwable","Grenade"), 1);
        assertValue(facts, BingoMissionType.LONG_DISTANCE_KILL, Map.of("distance",300), 1);
        assertValue(facts, BingoMissionType.WALL_PENETRATION_KILLS, Map.of(), 1);
        assertValue(facts, BingoMissionType.THROWABLE_USED, Map.of("throwable","SmokeBomb"), 3);
        assertValue(facts, BingoMissionType.PLAY_WITH_CLAN_MEMBERS, Map.of("clanMemberCount",3), 1);
        for (BingoMissionType type : List.of(BingoMissionType.REVIVES, BingoMissionType.CARRY,
                BingoMissionType.VAULT_COUNT, BingoMissionType.LEDGE_GRAB_COUNT,
                BingoMissionType.CARE_PACKAGE_PICKUP, BingoMissionType.WHEEL_DESTROY_COUNT))
            assertThat(engine.value(type, facts, Map.of())).isEqualByComparingTo(facts.metric(type.name()));
    }

    @Test
    void clanPlayRequirementAppliesOnlyToTheConfiguredCell() {
        BingoProgress clanOnly = progress(cell(BingoMissionType.KILLS, BingoAggregationType.EVENT_TOTAL,
                5, null, Map.of("clanPlayRequired", true)));
        BingoProgress personalAllowed = progress(cell(BingoMissionType.KILLS, BingoAggregationType.EVENT_TOTAL,
                5, null, Map.of()));
        PlayerMatchFacts solo = new PlayerMatchFacts("solo", playedAt, "Erangel_Main", "squad",
                Map.of("KILLS", bd(5)), List.of(), Map.of(), 0, playedAt);

        apply(clanOnly, solo);
        apply(personalAllowed, solo);

        assertThat(clanOnly.getCurrentValue()).isZero();
        assertThat(clanOnly.isCompleted()).isFalse();
        assertThat(personalAllowed.getCurrentValue()).isEqualByComparingTo("5");
        assertThat(personalAllowed.isCompleted()).isTrue();
    }

    @Test
    void newMissionsReuseFactsAndMatchOnlyConfiguredItemsAndVehicleClanCount() {
        PlayerMatchFacts facts = new PlayerMatchFacts("m", playedAt, "Tiger_Main", "squad",
                Map.of("VEHICLE_DESTROY_COUNT",bd(2),"VEHICLE_DAMAGE",bd(600),"ARMOR_DESTROY_COUNT",bd(1),
                        "FREEFALL_DISTANCE",bd(1200),"ENEMY_LOOTBOX_PICKUP",bd(4),"EMERGENCY_PICKUP_RIDE",bd(1),
                        "BREACHABLE_WALL_DESTROY_COUNT",bd(3),"MAX_CLAN_VEHICLE_PASSENGERS",bd(2)),
                List.of(), Map.of(), Map.of("Item_Heal_FirstAid_C",2),
                Map.of("Item_Tiger_SelfRevive_C",1), Map.of(), Map.of(), 2, playedAt);

        assertValue(facts, BingoMissionType.VEHICLE_DESTROY_COUNT, Map.of(), 2);
        assertValue(facts, BingoMissionType.VEHICLE_DAMAGE, Map.of(), 600);
        assertValue(facts, BingoMissionType.ARMOR_DESTROY_COUNT, Map.of(), 1);
        assertValue(facts, BingoMissionType.FREEFALL_DISTANCE, Map.of(), 1200);
        assertValue(facts, BingoMissionType.ITEM_PICKUP, Map.of("itemId","Item_Heal_FirstAid_C"), 2);
        assertValue(facts, BingoMissionType.ITEM_PICKUP, Map.of("itemId","Item_Heal_MedKit_C"), 0);
        assertValue(facts, BingoMissionType.ITEM_USE, Map.of("itemId","Item_Tiger_SelfRevive_C"), 1);
        assertValue(facts, BingoMissionType.ENEMY_LOOTBOX_PICKUP, Map.of(), 4);
        assertValue(facts, BingoMissionType.EMERGENCY_PICKUP_RIDE, Map.of(), 1);
        assertValue(facts, BingoMissionType.BREACHABLE_WALL_DESTROY_COUNT, Map.of(), 3);
        assertValue(facts, BingoMissionType.RIDE_WITH_CLAN_MEMBERS, Map.of("clanMemberCount",2), 1);
        assertValue(facts, BingoMissionType.RIDE_WITH_CLAN_MEMBERS, Map.of("clanMemberCount",3), 0);
    }

    @Test
    void vehicleDestroySupportsEventTotalSingleMatchAndOccurrenceAggregation() {
        PlayerMatchFacts one = facts(Map.of("VEHICLE_DESTROY_COUNT", bd(1)));
        PlayerMatchFacts two = facts(Map.of("VEHICLE_DESTROY_COUNT", bd(2)));
        BingoProgress total = progress(cell(BingoMissionType.VEHICLE_DESTROY_COUNT, BingoAggregationType.EVENT_TOTAL, 3, null, null));
        apply(total, one); apply(total, two);
        assertThat(total.getCurrentValue()).isEqualByComparingTo("3"); assertThat(total.isCompleted()).isTrue();

        BingoProgress single = progress(cell(BingoMissionType.VEHICLE_DESTROY_COUNT, BingoAggregationType.SINGLE_MATCH, 2, null, null));
        apply(single, one); apply(single, two);
        assertThat(single.getCurrentValue()).isEqualByComparingTo("2"); assertThat(single.isCompleted()).isTrue();

        BingoProgress occurrences = progress(cell(BingoMissionType.VEHICLE_DESTROY_COUNT, BingoAggregationType.MATCH_OCCURRENCES, 2, 2, null));
        apply(occurrences, two); apply(occurrences, one); apply(occurrences, two);
        assertThat(occurrences.getOccurrenceCount()).isEqualTo(2); assertThat(occurrences.isCompleted()).isTrue();
    }

    @Test
    void lineCalculatorFindsRowsColumnsAndBothDiagonalsWithoutDuplicates() {
        BingoLineCalculator calculator = new BingoLineCalculator();
        Set<Integer> positions = Set.of(0,1,2,3,4,5,6,7,8);
        Set<String> lines = calculator.completedLines(3, positions);
        assertThat(lines).containsExactlyInAnyOrder("ROW_0","ROW_1","ROW_2","COL_0","COL_1","COL_2","DIAG_MAIN","DIAG_ANTI");
        assertThat(lines).hasSize(8);
    }

    private void assertValue(PlayerMatchFacts facts, BingoMissionType type, Map<String,Object> options, int expected) {
        assertThat(engine.value(type, facts, options)).isEqualByComparingTo(Integer.toString(expected));
    }
    private BingoCell cell(BingoMissionType type, BingoAggregationType aggregation, int target, Integer occurrences, Map<String,Object> options) {
        return new BingoCell(null, 0, type, aggregation, BingoOperator.GREATER_THAN_OR_EQUAL, bd(target), occurrences, options, null);
    }
    private BingoProgress progress(BingoCell cell) { return new BingoProgress(null, cell, playedAt); }
    private void apply(BingoProgress progress, PlayerMatchFacts facts) {
        var result = engine.apply(progress.getCell(), progress, facts);
        progress.apply(result.value(), result.occurrences(), result.completed(), facts.matchId(), playedAt, playedAt);
    }
    private PlayerMatchFacts facts(Map<String,BigDecimal> metrics) { return new PlayerMatchFacts("m",playedAt,"Erangel_Main","squad",metrics,List.of(),Map.of(),0,playedAt); }
    private BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
}
