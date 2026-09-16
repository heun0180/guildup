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
