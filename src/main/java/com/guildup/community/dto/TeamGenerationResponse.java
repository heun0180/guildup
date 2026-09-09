package com.guildup.community.dto;

import java.util.List;

public record TeamGenerationResponse(
        String currentSeasonId,
        String previousSeasonId,
        List<String> selectedSeasonIds,
        List<TeamMakerParticipantResponse> participants,
        List<TeamMakerParticipantResponse> missingStatsParticipants,
        List<BalancedTeamResponse> teams,
        TeamBalanceSummaryResponse balance
) {}
