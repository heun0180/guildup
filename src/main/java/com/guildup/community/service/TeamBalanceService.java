package com.guildup.community.service;

import com.guildup.community.dto.BalancedTeamResponse;
import com.guildup.community.dto.TeamBalanceSummaryResponse;
import com.guildup.community.dto.TeamMakerParticipantResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 인원 차이를 1명 이내로 유지하고 빈 자리를 0 딜량으로 계산해 팀 전체 전력을 맞춘다. */
@Service
public class TeamBalanceService {

    public BalanceResult balance(List<TeamMakerParticipantResponse> participants, int maxMembersPerTeam, long seed) {
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("팀을 만들 참가자가 필요합니다.");
        }
        if (maxMembersPerTeam < 1 || maxMembersPerTeam > 10) {
            throw new IllegalArgumentException("팀당 최대 인원은 1명부터 10명까지 선택할 수 있습니다.");
        }
        if (participants.stream().anyMatch(player -> player.averageDamage() == null)) {
            throw new IllegalArgumentException("평균 딜량이 없는 참가자는 팀을 만들 수 없습니다.");
        }

        int teamCount = (participants.size() + maxMembersPerTeam - 1) / maxMembersPerTeam;
        int baseSize = participants.size() / teamCount;
        int largerTeams = participants.size() % teamCount;
        Random random = new Random(seed);
        List<MutableTeam> teams = new ArrayList<>();
        for (int index = 0; index < teamCount; index++) {
            teams.add(new MutableTeam(index < largerTeams ? baseSize + 1 : baseSize));
        }
        java.util.Collections.shuffle(teams, random);

        List<RankedParticipant> ranked = participants.stream()
                .map(player -> new RankedParticipant(player, 0.96 + random.nextDouble() * 0.08))
                .sorted(Comparator.comparingDouble(
                        (RankedParticipant item) -> item.participant().averageDamage() * item.jitter()
                ).reversed())
                .toList();
        for (RankedParticipant item : ranked) {
            MutableTeam target = teams.stream()
                    .filter(team -> team.members.size() < team.targetSize)
                    .min(Comparator.comparingDouble(team -> projectedLoad(team, item.participant())))
                    .orElseThrow();
            target.add(item.participant());
        }

        improveBySwapping(teams);
        teams.sort(Comparator.comparingInt((MutableTeam team) -> team.targetSize).reversed()
                .thenComparing(Comparator.comparingDouble(MutableTeam::total).reversed()));

        List<BalancedTeamResponse> responses = new ArrayList<>();
        for (int index = 0; index < teams.size(); index++) {
            MutableTeam team = teams.get(index);
            team.members.sort(Comparator.comparingDouble(TeamMakerParticipantResponse::averageDamage).reversed());
            responses.add(new BalancedTeamResponse(
                    index + 1, team.members.size(), round(team.average()),
                    round(team.total() / maxMembersPerTeam), round(team.total()),
                    List.copyOf(team.members)
            ));
        }
        return new BalanceResult(responses, summarize(responses));
    }

    private double projectedLoad(MutableTeam team, TeamMakerParticipantResponse participant) {
        return team.total() + participant.averageDamage();
    }

    private void improveBySwapping(List<MutableTeam> teams) {
        double currentScore = score(teams);
        for (int pass = 0; pass < 200; pass++) {
            Swap best = null;
            double bestScore = currentScore;
            for (int left = 0; left < teams.size(); left++) {
                for (int right = left + 1; right < teams.size(); right++) {
                    MutableTeam a = teams.get(left);
                    MutableTeam b = teams.get(right);
                    for (int ai = 0; ai < a.members.size(); ai++) {
                        for (int bi = 0; bi < b.members.size(); bi++) {
                            swap(a.members, ai, b.members, bi);
                            double candidate = score(teams);
                            swap(a.members, ai, b.members, bi);
                            if (candidate + 1e-10 < bestScore) {
                                bestScore = candidate;
                                best = new Swap(a, ai, b, bi);
                            }
                        }
                    }
                }
            }
            if (best == null) break;
            swap(best.left.members, best.leftIndex, best.right.members, best.rightIndex);
            currentScore = bestScore;
        }
    }

    private double score(List<MutableTeam> teams) {
        double meanTotal = teams.stream().mapToDouble(MutableTeam::total).average().orElse(1);
        return teams.stream()
                .mapToDouble(team -> square((team.total() - meanTotal) / safe(meanTotal)))
                .average().orElse(0);
    }

    private TeamBalanceSummaryResponse summarize(List<BalancedTeamResponse> teams) {
        double minAverage = teams.stream().mapToDouble(BalancedTeamResponse::averageDamage).min().orElse(0);
        double maxAverage = teams.stream().mapToDouble(BalancedTeamResponse::averageDamage).max().orElse(0);
        double minAdjustedAverage = teams.stream().mapToDouble(BalancedTeamResponse::adjustedAverageDamage).min().orElse(0);
        double maxAdjustedAverage = teams.stream().mapToDouble(BalancedTeamResponse::adjustedAverageDamage).max().orElse(0);
        double minTotal = teams.stream().mapToDouble(BalancedTeamResponse::totalDamage).min().orElse(0);
        double maxTotal = teams.stream().mapToDouble(BalancedTeamResponse::totalDamage).max().orElse(0);
        return new TeamBalanceSummaryResponse(
                minAverage, maxAverage, round(maxAverage - minAverage),
                minAdjustedAverage, maxAdjustedAverage, round(maxAdjustedAverage - minAdjustedAverage),
                minTotal, maxTotal, round(maxTotal - minTotal)
        );
    }

    private double safe(double value) { return value == 0 ? 1 : value; }
    private double square(double value) { return value * value; }
    private double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private void swap(List<TeamMakerParticipantResponse> left, int leftIndex,
                      List<TeamMakerParticipantResponse> right, int rightIndex) {
        TeamMakerParticipantResponse value = left.get(leftIndex);
        left.set(leftIndex, right.get(rightIndex));
        right.set(rightIndex, value);
    }

    private static final class MutableTeam {
        private final int targetSize;
        private final List<TeamMakerParticipantResponse> members = new ArrayList<>();
        private MutableTeam(int targetSize) { this.targetSize = targetSize; }
        private void add(TeamMakerParticipantResponse participant) { members.add(participant); }
        private double total() { return members.stream().mapToDouble(TeamMakerParticipantResponse::averageDamage).sum(); }
        private double average() { return members.isEmpty() ? 0 : total() / members.size(); }
    }

    private record RankedParticipant(TeamMakerParticipantResponse participant, double jitter) {}
    private record Swap(MutableTeam left, int leftIndex, MutableTeam right, int rightIndex) {}
    public record BalanceResult(List<BalancedTeamResponse> teams, TeamBalanceSummaryResponse summary) {}
}
