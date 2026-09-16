package com.guildup.killcompetition.dto;

import com.guildup.killcompetition.domain.*;
import java.time.Instant;

public record KillCompetitionSummaryResponse(
        Long id, String title, KillCompetitionGameMode gameMode, String status,
        int participantCount, Long creatorMemberId, String creatorNickname,
        Instant endsAt, Instant startedAt, Instant serverTime
) {
    public static KillCompetitionSummaryResponse from(KillCompetition competition, Instant now) {
        return new KillCompetitionSummaryResponse(
                competition.getId(), competition.getTitle(), competition.getGameMode(),
                effectiveStatus(competition, now), (int) competition.getParticipants().stream()
                        .filter(KillCompetitionParticipant::isApproved).count(),
                competition.getCreatedBy().getId(), competition.getCreatedBy().getNickname(),
                competition.getEndsAt(), competition.getStartedAt(), now
        );
    }

    public static String effectiveStatus(KillCompetition competition, Instant now) {
        if (competition.getStatus() == KillCompetitionStatus.IN_PROGRESS
                && !now.isBefore(competition.getEndsAt())) return "ENDED";
        return competition.getStatus().name();
    }
}
