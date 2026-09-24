package com.guildup.bingo.mission;

import com.guildup.bingo.domain.*;
import com.guildup.pubg.model.PlayerMatchFacts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 미션 종류와 집계 방식을 분리해 같은 Fact를 모든 규칙이 재사용하게 한다. */
@Component
public class BingoMissionEngine {
    public Outcome apply(BingoCell cell, BingoProgress progress, PlayerMatchFacts facts) {
        Outcome current = progress == null ? Outcome.zero()
                : new Outcome(progress.getCurrentValue(), progress.getOccurrenceCount(), progress.isCompleted());
        return apply(cell, current, facts);
    }

    /** 저장 엔티티 없이도 동일한 미션 계산식을 순차 재생할 수 있다. */
    public Outcome apply(BingoCell cell, Outcome current, PlayerMatchFacts facts) {
        Map<String, Object> options = cell.getOptions();
        if (!matchesFilter(options, facts)) return current;
        BigDecimal matchValue = value(cell.getMissionType(), facts, options);
        BigDecimal currentValue = current.value();
        int occurrences = current.occurrences();
        return switch (cell.getAggregationType()) {
            case EVENT_TOTAL -> {
                BigDecimal next = currentValue.add(matchValue);
                yield new Outcome(next, occurrences, compare(next, cell.getOperator(), cell.getTargetValue()));
            }
            case SINGLE_MATCH -> {
                BigDecimal best = currentValue.max(matchValue);
                yield new Outcome(best, occurrences, compare(matchValue, cell.getOperator(), cell.getTargetValue()));
            }
            case MATCH_OCCURRENCES -> {
                int next = occurrences + (compare(matchValue, cell.getOperator(), cell.getTargetValue()) ? 1 : 0);
                yield new Outcome(currentValue.max(matchValue), next, next >= requiredOccurrences(cell));
            }
        };
    }

    public BigDecimal value(BingoMissionType type, PlayerMatchFacts facts, Map<String, Object> options) {
        return switch (type) {
            case KILLS -> {
                String weapon = string(options, "weapon"), category = string(options, "weaponCategory");
                if (weapon != null && !weapon.isBlank()) yield countKills(facts, kill -> BingoWeaponCatalog.same(weapon, kill.weapon()));
                if (category != null && !category.isBlank()) yield countKills(facts, kill -> Objects.equals(category, kill.weaponCategory()));
                yield facts.metric(type.name());
            }
            case LONG_DISTANCE_KILL -> {
                double minimumMeters = number(options, "distance", Double.NaN);
                if (!Double.isFinite(minimumMeters) || minimumMeters <= 0) yield BigDecimal.ZERO;
                yield countKills(facts, kill -> kill.distance() >= minimumMeters);
            }
            case WEAPON_KILLS -> countKills(facts, kill -> BingoWeaponCatalog.same(string(options, "weapon"), kill.weapon()));
            case WEAPON_CATEGORY_KILLS -> countKills(facts, kill -> Objects.equals(string(options, "weaponCategory"), kill.weaponCategory()));
            case THROWABLE_KILLS -> countKills(facts, kill -> BingoWeaponCatalog.same(string(options, "throwable"), kill.throwable()));
            case WALL_PENETRATION_KILLS -> countKills(facts, PlayerMatchFacts.KillFact::wallPenetration);
            case THROWABLE_USED -> BigDecimal.valueOf(facts.throwableUses().entrySet().stream()
                    .filter(e -> BingoWeaponCatalog.same(string(options, "throwable"), e.getKey()))
                    .mapToInt(Map.Entry::getValue).sum());
            case PLAY_WITH_CLAN_MEMBERS -> BigDecimal.valueOf(facts.clanMembersInTeam() >= number(options, "clanMemberCount", 1) ? 1 : 0);
            case ITEM_PICKUP -> itemCount(facts.pickedItems(), string(options, "itemId"));
            case ITEM_USE -> itemCount(facts.usedItems(), string(options, "itemId"));
            case CARE_PACKAGE_PICKUP -> blank(string(options, "itemId"))
                    ? facts.metric(type.name()) : itemCount(facts.carePackageItems(), string(options, "itemId"));
            case ARMOR_DESTROY_COUNT -> blank(string(options, "itemId"))
                    ? facts.metric(type.name()) : itemCount(facts.destroyedArmor(), string(options, "itemId"));
            case RIDE_WITH_CLAN_MEMBERS -> BigDecimal.valueOf(
                    facts.metric("MAX_CLAN_VEHICLE_PASSENGERS").doubleValue() >= number(options, "clanMemberCount", 1) ? 1 : 0);
            default -> facts.metric(type.name());
        };
    }

    private BigDecimal itemCount(Map<String, Integer> values, String itemId) {
        return BigDecimal.valueOf(itemId == null ? 0 : values.getOrDefault(itemId, 0));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private BigDecimal countKills(PlayerMatchFacts facts, java.util.function.Predicate<PlayerMatchFacts.KillFact> test) {
        return BigDecimal.valueOf(facts.kills().stream().filter(test).count());
    }
    private boolean matchesFilter(Map<String, Object> options, PlayerMatchFacts facts) {
        String map = string(options, "map");
        String mode = string(options, "gameMode");
        return (map == null || map.isBlank() || map.equalsIgnoreCase(facts.mapName()))
                && (mode == null || mode.isBlank() || mode.equalsIgnoreCase(facts.gameMode()))
                && (!Boolean.TRUE.equals(options.get("clanPlayRequired")) || facts.clanMembersInTeam() >= 1);
    }
    private boolean compare(BigDecimal actual, BingoOperator operator, BigDecimal target) {
        int comparison = actual.compareTo(target);
        return switch (operator) {
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case EQUAL -> comparison == 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
        };
    }
    private int requiredOccurrences(BingoCell cell) { return cell.getOccurrenceTarget() == null ? 1 : cell.getOccurrenceTarget(); }
    private String string(Map<String, Object> options, String key) {
        Object value = options.get(key); return value == null ? null : String.valueOf(value);
    }
    private double number(Map<String, Object> options, String key, double fallback) {
        Object value = options.get(key); return value instanceof Number n ? n.doubleValue() : fallback;
    }
    public record Outcome(BigDecimal value, int occurrences, boolean completed) {
        public static Outcome zero() { return new Outcome(BigDecimal.ZERO, 0, false); }
        static Outcome unchanged(BingoProgress progress) {
            return progress == null ? zero()
                    : new Outcome(progress.getCurrentValue(), progress.getOccurrenceCount(), progress.isCompleted());
        }
    }
}
