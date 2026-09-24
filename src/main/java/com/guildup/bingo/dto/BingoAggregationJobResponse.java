package com.guildup.bingo.dto;

import java.time.Instant;

public record BingoAggregationJobResponse(
        Long bingoId,
        String state,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer processedMatches,
        Integer updatedParticipants,
        Instant aggregatedAt,
        String message
) {
    public static BingoAggregationJobResponse idle(Long bingoId) {
        return new BingoAggregationJobResponse(bingoId, "IDLE", null, null, null,
                null, null, null, null);
    }
}
