package com.guildup.bingo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BingoDetailResponse(
        Long id, String title, String description, int boardSize, int targetLines,
        boolean blackoutEnabled, boolean allowLateJoin, Instant startsAt, Instant endsAt,
        String status, Instant lastAggregatedAt, boolean admin, List<Cell> cells,
        PlayerBoard me, List<Participant> participants
) {
    public record Cell(Long id, int position, String missionType, String aggregationType,
                       String operator, BigDecimal targetValue, Integer occurrenceTarget,
                       Map<String, Object> options, String customTitle, String title) {}
    public record PlayerBoard(Long participantId, String nickname, boolean pubgConnected,
                              String pubgNickname, int completedCells, int lineCount,
                              Instant targetLinesCompletedAt, Instant blackoutCompletedAt,
                              List<Progress> progress) {}
    public record Progress(Long cellId, BigDecimal currentValue, int occurrenceCount,
                           boolean completed, Instant completedAt, String evidenceMatchId,
                           Instant evidenceEventAt) {}
    public record Participant(Long participantId, String nickname, int completedCells,
                              int lineCount, Instant targetLinesCompletedAt, Instant blackoutCompletedAt) {}
}
