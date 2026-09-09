package com.guildup.community.dto;

import java.util.List;

public record TeamRebalanceRequest(
        List<TeamMakerParticipantResponse> participants,
        int maxMembersPerTeam,
        Long seed
) {}
