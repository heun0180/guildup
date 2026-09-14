package com.guildup.killcompetition.dto;

import java.util.List;

public record KillCompetitionTeamRequest(int teamCount, List<TeamAssignment> assignments) {
    public record TeamAssignment(Long participantId, int teamNumber) {}
}
