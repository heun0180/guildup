package com.guildup.bingo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** TEMPORARY: 2026-09 bingo progress repair tool. Remove after current event verification. */
public record TemporaryBingoRebuildResponse(
        String status,
        String previewToken,
        Long bingoId,
        String bingoTitle,
        Instant calculatedAt,
        int participantCount,
        int matchCount,
        int changedProgressCount,
        List<ParticipantResult> participants,
        List<Failure> failures
) {
    public record ParticipantResult(Long participantId, String pubgNickname,
                                    List<ProgressChange> changes) {}
    public record ProgressChange(Long cellId, String missionType, String title,
                                 BigDecimal previousValue, BigDecimal recomputedValue,
                                 boolean previousCompleted, boolean recomputedCompleted) {}
    public record Failure(Long participantId, String pubgNickname, String matchId, String reason) {}
}
