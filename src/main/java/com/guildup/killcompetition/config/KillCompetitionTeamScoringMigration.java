package com.guildup.killcompetition.config;

import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 기존 개인 점수에서 팀 보너스를 제거하고 같은 계산기로 팀별 스냅샷을 한 번 전환한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class KillCompetitionTeamScoringMigration implements ApplicationRunner {
    private final KillCompetitionRepository competitions;
    private final KillCompetitionMatchResultRepository matchResults;

    public KillCompetitionTeamScoringMigration(KillCompetitionRepository competitions,
                                               KillCompetitionMatchResultRepository matchResults) {
        this.competitions = competitions;
        this.matchResults = matchResults;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Long id : competitions.findLegacyTeamScoringIds()) {
            KillCompetition competition = competitions.findByIdForUpdate(id).orElseThrow();
            List<KillCompetitionMatchResult> rows = matchResults
                    .findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(id);
            Instant latestInterimMatch = competition.getLastInterimMatchStartedAt();
            var interim = KillCompetitionScoring.calculate(competition, rows.stream()
                    .filter(row -> latestInterimMatch != null && !row.getMatchStartedAt().isAfter(latestInterimMatch)).toList());
            var finalTotals = KillCompetitionScoring.calculate(competition, rows);
            for (KillCompetitionParticipant participant : competition.getParticipants()) {
                participant.recordInterim(participant.getInterimKills(), participant.getInterimMatchCount(),
                        Math.multiplyExact(participant.getInterimKills(), competition.getKillPoint()));
                if (participant.getFinalKills() != null) {
                    participant.recordFinal(participant.getFinalKills(), Optional.ofNullable(participant.getFinalMatchCount()).orElse(0),
                            Math.multiplyExact(participant.getFinalKills(), competition.getKillPoint()));
                }
            }
            for (KillCompetitionMatchResult row : rows) {
                var score = KillCompetitionScoring.personalMatchScore(competition, row.getKills(), row.getPlacement());
                row.refresh(row.getMatchStartedAt(), row.getKills(), row.getPlacement() == null ? 0 : row.getPlacement(),
                        score.points(), 0, score.points());
            }
            for (KillCompetitionTeam team : competition.getTeams()) {
                team.recordInterimPlacementPoints(interim.teamPlacementPoints().getOrDefault(team.getId(), 0));
                if (competition.getStatus() == KillCompetitionStatus.COMPLETED) {
                    team.recordFinalPlacementPoints(finalTotals.teamPlacementPoints().getOrDefault(team.getId(), 0));
                }
            }
            // 과거에 지급한 활동 점수 원장/빙고 진행도는 이 점수 데이터 전환에서 변경하지 않는다.
        }
    }
}
