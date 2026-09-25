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
        String message,
        String stage,
        Integer stageCompleted,
        Integer stageTotal,
        Integer telemetryFailures
) {
    public BingoAggregationJobResponse(Long bingoId, String state, Instant requestedAt, Instant startedAt,
            Instant finishedAt, Integer processedMatches, Integer updatedParticipants, Instant aggregatedAt,
            String message) {
        this(bingoId, state, requestedAt, startedAt, finishedAt, processedMatches, updatedParticipants,
                aggregatedAt, message, null, null, null, null);
    }
    public BingoAggregationJobResponse(Long bingoId, String state, Instant requestedAt, Instant startedAt,
            Instant finishedAt, Integer processedMatches, Integer updatedParticipants, Instant aggregatedAt,
            String message, String stage, Integer stageCompleted, Integer stageTotal) {
        this(bingoId, state, requestedAt, startedAt, finishedAt, processedMatches, updatedParticipants,
                aggregatedAt, message, stage, stageCompleted, stageTotal, null);
    }
    public static BingoAggregationJobResponse idle(Long bingoId) {
        return new BingoAggregationJobResponse(bingoId, "IDLE", null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
