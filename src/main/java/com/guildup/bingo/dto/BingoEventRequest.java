package com.guildup.bingo.dto;

import com.guildup.bingo.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BingoEventRequest(
        String title, String description, Instant startsAt, Instant endsAt,
        int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
        boolean excludeBotCombatStats, boolean clanPlayRequired,
        BingoStatus status, List<Cell> cells
) {
    public BingoEventRequest(String title, String description, Instant startsAt, Instant endsAt,
            int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
            BingoStatus status, List<Cell> cells) {
        this(title, description, startsAt, endsAt, boardSize, targetLines, blackoutEnabled, allowLateJoin,
                false, false, status, cells);
    }
    public BingoEventRequest(String title, String description, Instant startsAt, Instant endsAt,
            int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
            boolean excludeBotCombatStats, BingoStatus status, List<Cell> cells) {
        this(title, description, startsAt, endsAt, boardSize, targetLines, blackoutEnabled, allowLateJoin,
                excludeBotCombatStats, false, status, cells);
    }
    public record Cell(int position, BingoMissionType missionType, BingoAggregationType aggregationType,
                       BingoOperator operator, BigDecimal targetValue, Integer occurrenceTarget,
                       Map<String, Object> options, String customTitle) {}
}
