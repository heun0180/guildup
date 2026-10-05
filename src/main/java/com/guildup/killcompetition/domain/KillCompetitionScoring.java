package com.guildup.killcompetition.domain;

import java.util.*;

/** 개인 기본 점수와 경기별 팀 보너스를 중간/최종 정산, 조회, 우승자 판정에서 공유한다. */
public final class KillCompetitionScoring {
    private KillCompetitionScoring() {}

    public record Score(int kills, int placementPoints, int points) {}
    public record PlayerTotal(int kills, int matchCount, int points) {
        public static final PlayerTotal ZERO = new PlayerTotal(0, 0, 0);
    }
    public record TeamMatchKey(Long teamId, String matchId) {}
    public record Totals(Map<Long, PlayerTotal> players, Map<Long, Integer> teamPlacementPoints) {}

    public static Score personalMatchScore(KillCompetition competition, int kills, Integer placement) {
        int bonus = competition.getGameMode() == KillCompetitionGameMode.SOLO
                ? competition.placementPointFor(placement == null ? 0 : placement) : 0;
        return score(competition, kills, bonus);
    }

    public static Totals calculate(KillCompetition competition, List<KillCompetitionMatchResult> rows) {
        Map<Long, PlayerTotal> players = new HashMap<>();
        for (KillCompetitionMatchResult row : rows) {
            if (!row.getParticipant().isApproved()) continue;
            Long id = row.getParticipant().getId();
            PlayerTotal old = players.getOrDefault(id, PlayerTotal.ZERO);
            Score score = personalMatchScore(competition, row.getKills(), row.getPlacement());
            players.put(id, new PlayerTotal(Math.addExact(old.kills(), score.kills()),
                    Math.addExact(old.matchCount(), 1), Math.addExact(old.points(), score.points())));
        }
        Map<Long, Integer> bonuses = new HashMap<>();
        teamMatchScores(competition, rows).forEach((key, score) ->
                bonuses.merge(key.teamId(), score.placementPoints(), Math::addExact));
        return new Totals(Map.copyOf(players), Map.copyOf(bonuses));
    }

    public static Map<TeamMatchKey, Score> teamMatchScores(KillCompetition competition,
                                                         List<KillCompetitionMatchResult> rows) {
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) return Map.of();
        Map<TeamMatchKey, List<KillCompetitionMatchResult>> grouped = new LinkedHashMap<>();
        for (KillCompetitionMatchResult row : rows) {
            KillCompetitionParticipant participant = row.getParticipant();
            if (!participant.isApproved() || participant.getTeam() == null) continue;
            TeamMatchKey key = new TeamMatchKey(participant.getTeam().getId(), row.getMatchId());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Map<TeamMatchKey, Score> scores = new LinkedHashMap<>();
        grouped.forEach((key, group) -> {
            int kills = group.stream().mapToInt(KillCompetitionMatchResult::getKills).reduce(0, Math::addExact);
            // 같은 팀의 PUBG 등수는 동일하다. 누락/불일치가 있으면 유효한 가장 높은 등수를 한 번 사용한다.
            int placement = group.stream().map(KillCompetitionMatchResult::getPlacement).filter(Objects::nonNull)
                    .filter(value -> value > 0).mapToInt(Integer::intValue).min().orElse(0);
            scores.put(key, score(competition, kills, competition.placementPointFor(placement)));
        });
        return Collections.unmodifiableMap(scores);
    }

    public static Score teamScore(KillCompetition competition, KillCompetitionTeam team, boolean finalResult) {
        int kills = 0;
        int basePoints = 0;
        for (KillCompetitionParticipant participant : competition.getParticipants()) {
            if (!participant.isApproved() || participant.getTeam() == null
                    || !Objects.equals(participant.getTeam().getId(), team.getId())) continue;
            kills = Math.addExact(kills, finalResult
                    ? Optional.ofNullable(participant.getFinalKills()).orElse(0) : participant.getInterimKills());
            basePoints = Math.addExact(basePoints, finalResult
                    ? Optional.ofNullable(participant.getFinalPoints()).orElse(0) : participant.getInterimPoints());
        }
        int bonus = finalResult ? Optional.ofNullable(team.getFinalPlacementPoints()).orElse(0)
                : team.getInterimPlacementPoints();
        return new Score(kills, bonus, Math.addExact(basePoints, bonus));
    }

    private static Score score(KillCompetition competition, int kills, int placementPoints) {
        return new Score(kills, placementPoints,
                Math.addExact(Math.multiplyExact(kills, competition.getKillPoint()), placementPoints));
    }
}
