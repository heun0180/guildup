package com.guildup.killcompetition.dto;

import com.guildup.killcompetition.domain.*;
import java.time.Instant;
import java.util.*;

public record KillCompetitionDetailResponse(
        Long id, String title, KillCompetitionGameMode gameMode, String status,
        int killPoint, boolean placementPointEnabled,
        int firstPlacePoint, int secondPlacePoint, int thirdPlacePoint,
        int fourthPlacePoint, int fifthPlacePoint, int sixthPlacePoint, int seventhPlacePoint,
        int eighthPlacePoint, int ninthPlacePoint, int tenthPlacePoint,
        int fourthFifthPlacePoint, int sixthTenthPlacePoint,
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
                              int interimKills, int interimMatchCount, int interimPlacementPoints, int interimPoints,
                              Integer finalKills, Integer finalMatchCount,
                              Integer finalPlacementPoints, Integer finalPoints) {}
    public record Team(Long teamId, String name, int displayOrder, List<Long> participantIds,
                       int interimKills, int interimPlacementPoints, int interimPoints,
                       Integer finalKills, Integer finalPlacementPoints, Integer finalPoints) {}
    public record Standing(int rank, Long participantId, Long teamId, String name,
                           int kills, int placementPoints, int points, boolean winner) {}
    public record Match(String matchId, Instant startedAt, List<MatchPlayer> players) {}
    public record MatchPlayer(Long participantId, String nickname, int kills, Integer placement,
                              int killPoints, int placementPoints, int totalPoints) {}

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
                p.getInterimKills(), p.getInterimMatchCount(),
                placementPoints(p.getInterimPoints(), p.getInterimKills(), competition.getKillPoint()), p.getInterimPoints(),
                p.getFinalKills(), p.getFinalMatchCount(),
                p.getFinalPoints() == null ? null : placementPoints(p.getFinalPoints(), p.getFinalKills(), competition.getKillPoint()),
                p.getFinalPoints()
        )).toList();
        List<Team> teamDtos = competition.getTeams().stream().map(team -> {
            List<KillCompetitionParticipant> members = approved.stream()
                    .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), team.getId())).toList();
            Integer finalKills = members.stream().allMatch(p -> p.getFinalKills() != null)
                    ? members.stream().mapToInt(p -> p.getFinalKills()).sum() : null;
            Integer finalPoints = members.stream().allMatch(p -> p.getFinalPoints() != null)
                    ? members.stream().mapToInt(p -> p.getFinalPoints()).sum() : null;
            int interimKills = members.stream().mapToInt(KillCompetitionParticipant::getInterimKills).sum();
            int interimPoints = members.stream().mapToInt(KillCompetitionParticipant::getInterimPoints).sum();
            return new Team(team.getId(), team.getTeamName(), team.getDisplayOrder(),
                    members.stream().map(KillCompetitionParticipant::getId).toList(),
                    interimKills, placementPoints(interimPoints, interimKills, competition.getKillPoint()), interimPoints,
                    finalKills, finalPoints == null ? null : placementPoints(finalPoints, finalKills, competition.getKillPoint()),
                    finalPoints);
        }).toList();
        Long myParticipantId = competition.getParticipants().stream()
                .filter(p -> Objects.equals(p.getCommunityMember().getId(), currentMemberId))
                .map(KillCompetitionParticipant::getId).findFirst().orElse(null);
        return new KillCompetitionDetailResponse(
                competition.getId(), competition.getTitle(), competition.getGameMode(),
                KillCompetitionSummaryResponse.effectiveStatus(competition, now),
                competition.getKillPoint(), competition.isPlacementPointEnabled(),
                competition.getFirstPlacePoint(), competition.getSecondPlacePoint(), competition.getThirdPlacePoint(),
                competition.getFourthPlacePoint(), competition.getFifthPlacePoint(), competition.getSixthPlacePoint(),
                competition.getSeventhPlacePoint(), competition.getEighthPlacePoint(), competition.getNinthPlacePoint(),
                competition.getTenthPlacePoint(),
                competition.getFourthFifthPlacePoint(), competition.getSixthTenthPlacePoint(),
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
        record Entry(Long participantId, Long teamId, String name, int kills, int points) {}
        List<Entry> entries;
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) {
            entries = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).map(p -> new Entry(
                    p.getId(), null, p.getCommunityMember().getNickname(),
                    finalResult ? Optional.ofNullable(p.getFinalKills()).orElse(0) : p.getInterimKills(),
                    finalResult ? Optional.ofNullable(p.getFinalPoints()).orElse(0) : p.getInterimPoints()
            )).toList();
        } else {
            entries = competition.getTeams().stream().map(team -> {
                int kills = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                        .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), team.getId()))
                        .mapToInt(p -> finalResult ? Optional.ofNullable(p.getFinalKills()).orElse(0) : p.getInterimKills()).sum();
                int points = competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                        .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), team.getId()))
                        .mapToInt(p -> finalResult ? Optional.ofNullable(p.getFinalPoints()).orElse(0) : p.getInterimPoints()).sum();
                return new Entry(null, team.getId(), team.getTeamName(), kills, points);
            }).toList();
        }
        List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt(Entry::points).reversed()
                .thenComparing(Entry::name)).toList();
        List<Standing> result = new ArrayList<>();
        int previousPoints = Integer.MIN_VALUE;
        int rank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Entry entry = sorted.get(index);
            if (entry.points() != previousPoints) rank = index + 1;
            result.add(new Standing(rank, entry.participantId(), entry.teamId(), entry.name(),
                    entry.kills(), placementPoints(entry.points(), entry.kills(), competition.getKillPoint()),
                    entry.points(), finalResult && rank == 1));
            previousPoints = entry.points();
        }
        return result;
    }

    private static List<Match> matches(List<KillCompetitionMatchResult> rows) {
        Map<String, List<KillCompetitionMatchResult>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getMatchId(), ignored -> new ArrayList<>()).add(row));
        return grouped.values().stream().map(group -> new Match(
                group.getFirst().getMatchId(), group.getFirst().getMatchStartedAt(),
                group.stream().map(row -> new MatchPlayer(row.getParticipant().getId(),
                        row.getParticipant().getCommunityMember().getNickname(), row.getKills(), row.getPlacement(),
                        row.getKillPoints(), row.getPlacementPoints(), row.getTotalPoints())).toList()
        )).toList();
    }

    private static int placementPoints(int totalPoints, int kills, int killPoint) {
        return totalPoints - Math.multiplyExact(kills, killPoint);
    }
}
