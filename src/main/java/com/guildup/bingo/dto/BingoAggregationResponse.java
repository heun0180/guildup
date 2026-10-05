package com.guildup.bingo.dto;
import java.time.Instant;
import java.util.List;
import com.guildup.pubg.service.PubgMatchSyncService.CollectionFailure;
public record BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                       String status, Instant aggregatedAt, int telemetryFailures,
                                       List<CollectionFailure> collectionFailures) {
    public BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                    String status, Instant aggregatedAt) {
        this(bingoId, processedMatches, updatedParticipants, status, aggregatedAt, 0, List.of());
    }
    public BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                    String status, Instant aggregatedAt, int telemetryFailures) {
        this(bingoId, processedMatches, updatedParticipants, status, aggregatedAt, telemetryFailures, List.of());
    }

    public BingoAggregationResponse withTelemetryFailures(int failures) {
        return new BingoAggregationResponse(bingoId, processedMatches, updatedParticipants, status, aggregatedAt, failures, collectionFailures);
    }
    public BingoAggregationResponse withCollectionFailures(int telemetryFailures, List<CollectionFailure> failures) {
        return new BingoAggregationResponse(bingoId, processedMatches, updatedParticipants, status, aggregatedAt,
                telemetryFailures, List.copyOf(failures));
    }
}
