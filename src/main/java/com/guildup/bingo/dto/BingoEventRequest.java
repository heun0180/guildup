package com.guildup.bingo.dto;

import com.guildup.bingo.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BingoEventRequest(
        String title, String description, Instant startsAt, Instant endsAt,
        int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
        BingoStatus status, List<Cell> cells
) {
    public record Cell(int position, BingoMissionType missionType, BingoAggregationType aggregationType,
                       BingoOperator operator, BigDecimal targetValue, Integer occurrenceTarget,
                       Map<String, Object> options, String customTitle) {}
}
