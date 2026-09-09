package com.guildup.community.dto;

import java.util.List;

public record BalancedTeamResponse(
        int teamNumber,
        int memberCount,
        double averageDamage,
        double adjustedAverageDamage,
        double totalDamage,
        List<TeamMakerParticipantResponse> participants
) {}
