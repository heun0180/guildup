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
    public record Match(String matchId, Instant startedAt, List<MatchPlayer> players, List<MatchTeam> teams) {}
    public record MatchTeam(Long teamId, String name, int kills, int placementPoints, int totalPoints) {}
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
                (p.getCommunityMember().isAnonymized() ? null : p.getPubgNickname()), p.getTeam() == null ? null : p.getTeam().getId(),
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
            var interim = KillCompetitionScoring.teamScore(competition, team, false);
            var finalScore = KillCompetitionScoring.teamScore(competition, team, true);
            boolean finalized = members.stream().allMatch(p -> p.getFinalPoints() != null);
            return new Team(team.getId(), team.getTeamName(), team.getDisplayOrder(),
                    members.stream().map(KillCompetitionParticipant::getId).toList(),
                    interim.kills(), interim.placementPoints(), interim.points(),
                    finalized ? finalScore.kills() : null, finalized ? finalScore.placementPoints() : null,
                    finalized ? finalScore.points() : null);
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
                competition.getResultRequestedAt(), competition.getResultPublishAt(),
                competition.getResultLastError() == null ? null : "최종 결과 집계에 실패했습니다. 잠시 후 다시 시도해 주세요.",
                competition.getCompletedAt(), now,
                new Member(competition.getCreatedBy().getId(), competition.getCreatedBy().getNickname()),
                Objects.equals(competition.getCreatedBy().getId(), currentMemberId), administrator, canManage,
                myParticipantId, pubgConfigured, approved.size() >= 4,
                approved.size(), participantDtos, teamDtos,
                standings(competition, false), standings(competition, true), matches(competition, matchResults)
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
                var score = KillCompetitionScoring.teamScore(competition, team, finalResult);
                return new Entry(null, team.getId(), team.getTeamName(), score.kills(), score.points());
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

    private static List<Match> matches(KillCompetition competition, List<KillCompetitionMatchResult> rows) {
        Map<String, List<KillCompetitionMatchResult>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getMatchId(), ignored -> new ArrayList<>()).add(row));
        var teamScores = KillCompetitionScoring.teamMatchScores(competition, rows);
        return grouped.values().stream().map(group -> new Match(
                group.getFirst().getMatchId(), group.getFirst().getMatchStartedAt(),
                group.stream().map(row -> new MatchPlayer(row.getParticipant().getId(),
                        row.getParticipant().getCommunityMember().getNickname(), row.getKills(), row.getPlacement(),
                        row.getKillPoints(), row.getPlacementPoints(), row.getTotalPoints())).toList(),
                competition.getTeams().stream().filter(team -> teamScores.containsKey(
                        new KillCompetitionScoring.TeamMatchKey(team.getId(), group.getFirst().getMatchId())))
                        .map(team -> {
                            var score = teamScores.get(new KillCompetitionScoring.TeamMatchKey(team.getId(), group.getFirst().getMatchId()));
                            return new MatchTeam(team.getId(), team.getTeamName(), score.kills(), score.placementPoints(), score.points());
                        }).toList()
        )).toList();
    }

    private static int placementPoints(int totalPoints, int kills, int killPoint) {
        return totalPoints - Math.multiplyExact(kills, killPoint);
    }
}
