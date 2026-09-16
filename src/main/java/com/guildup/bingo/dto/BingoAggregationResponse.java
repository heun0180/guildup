package com.guildup.bingo.dto;
import java.time.Instant;
public record BingoAggregationResponse(Long bingoId, int processedMatches, int updatedParticipants,
                                       String status, Instant aggregatedAt) {}
