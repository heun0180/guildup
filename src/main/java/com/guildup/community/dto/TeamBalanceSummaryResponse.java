package com.guildup.community.dto;

public record TeamBalanceSummaryResponse(
        double lowestTeamAverage,
        double highestTeamAverage,
        double averageDifference,
        double lowestAdjustedAverage,
        double highestAdjustedAverage,
        double adjustedAverageDifference,
        double lowestTeamTotal,
        double highestTeamTotal,
        double totalDifference
) {}
