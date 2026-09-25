package com.guildup.bingo.dto;
import java.time.Instant;
public record BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                       String status, Instant aggregatedAt, int telemetryFailures) {
    public BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                    String status, Instant aggregatedAt) {
        this(bingoId, processedMatches, updatedParticipants, status, aggregatedAt, 0);
    }

    public BingoAggregationResponse withTelemetryFailures(int failures) {
        return new BingoAggregationResponse(bingoId, processedMatches, updatedParticipants, status, aggregatedAt, failures);
    }
}
