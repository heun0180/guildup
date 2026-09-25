package com.guildup.killcompetition.dto;

import com.guildup.killcompetition.domain.*;
import java.time.Instant;
import java.util.*;

public record KillCompetitionDetailResponse(
        Long id, String title, KillCompetitionGameMode gameMode, String status,
        Instant endsAt, Instant startedAt, boolean recruitmentOpen, Instant recruitmentClosedAt,
        Instant lastInterimCalculatedAt, Instant lastInterimMatchStartedAt,
        Instant resultRequestedAt, Instant resultPublishAt, String resultLastError,
        Instant completedAt, Instant serverTime,
        Member creator, boolean creatorView, boolean administratorView, boolean canManageKillGame,
        Long myParticipantId, boolean pubgNicknameConfigured,
        boolean scoreEligible, int participantCount,
        List<Participant> participants, List<Team> teams,
        List<Standing> interimStandings, List<Standing> finalStandings,
        List<Match> finalMatches
) {
    public record Member(Long memberId, String nickname) {}
    public record Participant(Long participantId, Long memberId, String nickname, String pubgNickname,
                              Long teamId, KillCompetitionParticipationStatus participationStatus, Instant eligibleFrom,
                              int interimKills, int interimMatchCount,
                              Integer finalKills, Integer finalMatchCount) {}
    public record Team(Long teamId, String name, int displayOrder, List<Long> participantIds,
                       int interimKills, Integer finalKills) {}
    public record Standing(int rank, Long participantId, Long teamId, String name, int kills, boolean winner) {}
    public record Match(String matchId, Instant startedAt, List<MatchPlayer> players) {}
    public record MatchPlayer(Long participantId, String nickname, int kills) {}

    public static KillCompetitionDetailResponse from(
            KillCompetition competition, Long currentMemberId, boolean administrator, boolean canManage,
            boolean pubgConfigured, Instant now, List<KillCompetitionMatchResult> matchResults
    ) {
        List<KillCompetitionParticipant> approved = competition.getParticipants().stream()
                .filter(KillCompetitionParticipant::isApproved).toList();
        List<Participant> participantDtos = competition.getParticipants().stream().map(p -> new Participant(
                p.getId(), p.getCommunityMember().getId(), p.getCommunityMember().getNickname(),
                p.getPubgNickname(), p.getTeam() == null ? null : p.getTeam().getId(),
                p.getParticipationStatus(), p.getEligibleFrom(),
                p.getInterimKills(), p.getInterimMatchCount(), p.getFinalKills(), p.getFinalMatchCount()
        )).toList();
        List<Team> teamDtos = competition.getTeams().stream().map(team -> {
            List<KillCompetitionParticipant> members = approved.stream()
                    .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), team.getId())).toList();
            Integer finalKills = members.stream().allMatch(p -> p.getFinalKills() != null)
                    ? members.stream().mapToInt(p -> p.getFinalKills()).sum() : null;
            return new Team(team.getId(), team.getTeamName(), team.getDisplayOrder(),
                    members.stream().map(KillCompetitionParticipant::getId).toList(),
                    members.stream().mapToInt(KillCompetitionParticipant::getInterimKills).sum(), finalKills);
        }).toList();
        Long myParticipantId = competition.getParticipants().stream()
                .filter(p -> Objects.equals(p.getCommunityMember().getId(), currentMemberId))
                .map(KillCompetitionParticipant::getId).findFirst().orElse(null);
        return new KillCompetitionDetailResponse(
                competition.getId(), competition.getTitle(), competition.getGameMode(),
                KillCompetitionSummaryResponse.effectiveStatus(competition, now),
                competition.getEndsAt(), competition.getStartedAt(), competition.isRecruitmentOpen(), competition.getRecruitmentClosedAt(),
                competition.getLastInterimCalculatedAt(), competition.getLastInterimMatchStartedAt(),
                competition.getResultRequestedAt(), competition.getResultPublishAt(), competition.getResultLastError(),
                competition.getCompletedAt(), now,
                new Member(competition.getCreatedBy().getId(), competition.getCreatedBy().getNickname()),
                Objects.equals(competition.getCreatedBy().getId(), currentMemberId), administrator, canManage,
                myParticipantId, pubgConfigured, approved.size() >= 4,
                approved.size(), participantDtos, teamDtos,
                standings(competition, false), standings(competition, true), matches(matchResults)
        );
    }

    private static List<Standing> standings(KillCompetition competition, boolean finalResult) {
        if (finalResult && competition.getStatus() != KillCompetitionStatus.COMPLETED) return List.of();
        record Entry(Long participantId, Long teamId, String name, int kills) {}
        List<Entry> entries;
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) {
            entries = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).map(p -> new Entry(
                    p.getId(), null, p.getCommunityMember().getNickname(),
                    finalResult ? Optional.ofNullable(p.getFinalKills()).orElse(0) : p.getInterimKills()
            )).toList();
        } else {
            entries = competition.getTeams().stream().map(team -> {
                int kills = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                        .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), team.getId()))
                        .mapToInt(p -> finalResult ? Optional.ofNullable(p.getFinalKills()).orElse(0) : p.getInterimKills()).sum();
                return new Entry(null, team.getId(), team.getTeamName(), kills);
            }).toList();
        }
        List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt(Entry::kills).reversed()
                .thenComparing(Entry::name)).toList();
        List<Standing> result = new ArrayList<>();
        int previousKills = Integer.MIN_VALUE;
        int rank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Entry entry = sorted.get(index);
            if (entry.kills() != previousKills) rank = index + 1;
            result.add(new Standing(rank, entry.participantId(), entry.teamId(), entry.name(),
                    entry.kills(), finalResult && rank == 1));
            previousKills = entry.kills();
        }
        return result;
    }

    private static List<Match> matches(List<KillCompetitionMatchResult> rows) {
        Map<String, List<KillCompetitionMatchResult>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getMatchId(), ignored -> new ArrayList<>()).add(row));
        return grouped.values().stream().map(group -> new Match(
                group.getFirst().getMatchId(), group.getFirst().getMatchStartedAt(),
                group.stream().map(row -> new MatchPlayer(row.getParticipant().getId(),
                        row.getParticipant().getCommunityMember().getNickname(), row.getKills())).toList()
        )).toList();
    }
}
