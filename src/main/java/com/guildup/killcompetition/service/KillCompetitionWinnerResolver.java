package com.guildup.killcompetition.service;

import com.guildup.community.domain.CommunityMember;
import com.guildup.killcompetition.domain.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** 확정 킬내기의 개인전/팀전 우승자 판정을 모든 소비자가 공유한다. */
@Component
public class KillCompetitionWinnerResolver {
    public List<CommunityMember> winnerMembers(KillCompetition competition) {
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) {
            int max = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                    .map(KillCompetitionParticipant::getFinalKills).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).max().orElse(0);
            return competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                    .filter(p -> Objects.equals(p.getFinalKills(), max))
                    .filter(p -> Optional.ofNullable(p.getFinalMatchCount()).orElse(0) > 0)
                    .map(KillCompetitionParticipant::getCommunityMember).toList();
        }
        Map<Long, Integer> totals = new HashMap<>();
        competition.getTeams().forEach(team -> totals.put(team.getId(), 0));
        competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                .filter(p -> p.getTeam() != null && p.getFinalKills() != null)
                .forEach(p -> totals.merge(p.getTeam().getId(), p.getFinalKills(), Integer::sum));
        int max = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<Long> winningTeams = new HashSet<>();
        totals.forEach((teamId, kills) -> { if (kills == max) winningTeams.add(teamId); });
        return competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                .filter(p -> p.getTeam() != null && winningTeams.contains(p.getTeam().getId()))
                .filter(p -> Optional.ofNullable(p.getFinalMatchCount()).orElse(0) > 0)
                .map(KillCompetitionParticipant::getCommunityMember).toList();
    }
}
