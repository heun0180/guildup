package com.guildup.community.dto;

import java.util.List;

public record TeamGenerationRequest(
        List<Long> participantIds,
        boolean currentSeason,
        boolean previousSeason,
        int maxMembersPerTeam,
        Long seed
) {}
