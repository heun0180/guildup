package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.CommunityMember;
import com.guildup.killcompetition.domain.KillCompetition;
import com.guildup.killcompetition.domain.KillCompetitionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** GuildUp 내부 콘텐츠의 확정 결과를 빙고 진행률로 변환한다. */
@Service
public class BingoGuildUpContentService {
    private final BingoEventRepository events;
    private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress;
    private final BingoProcessedSourceRepository processedSources;
    private final BingoProgressCompletionService completions;

    public BingoGuildUpContentService(BingoEventRepository events,
                                      BingoParticipantRepository participants,
                                      BingoProgressRepository progress,
                                      BingoProcessedSourceRepository processedSources,
                                      BingoProgressCompletionService completions) {
        this.events = events;
        this.participants = participants;
        this.progress = progress;
        this.processedSources = processedSources;
        this.completions = completions;
    }

    @Transactional
    public void applyCompletedKillCompetition(KillCompetition competition,
                                              Collection<CommunityMember> winners,
                                              Instant completedAt) {
        if (competition.getStatus() != KillCompetitionStatus.COMPLETED
                || competition.getCommunityGame() == null || completedAt == null || winners.isEmpty()) return;

        Long communityId = competition.getCommunity().getId();
        Long gameId = competition.getCommunityGame().getId();
        String sourceId = competition.getId().toString();
        Instant occurredAt = competition.getEndsAt();
        if (occurredAt == null) return;
        Set<Long> winnerMemberIds = new HashSet<>();
        winners.stream().filter(member -> member.getCommunity().getId().equals(communityId))
                .forEach(member -> winnerMemberIds.add(member.getId()));
        if (winnerMemberIds.isEmpty()) return;

        for (BingoEvent candidate : events.findByCommunityGameIdOrderByStartsAtDesc(gameId)) {
            if (!Set.of(BingoStatus.ACTIVE, BingoStatus.SETTLING, BingoStatus.COMPLETED).contains(candidate.getStatus())
                    || !candidate.getCommunity().getId().equals(communityId)
                    || occurredAt.isBefore(candidate.getStartsAt())
                    || !occurredAt.isBefore(candidate.getMatchStartUpperBoundExclusive())
                    || candidate.getCells().stream().noneMatch(cell -> cell.getMissionType() == BingoMissionType.KILL_BET_WIN)) {
                continue;
            }
            BingoEvent event = events.findForUpdate(candidate.getId()).orElseThrow();
            for (BingoParticipant participant : participants.findByEventIdOrderByIdAsc(event.getId())) {
                CommunityMember member = participant.getCommunityMember();
                if (member == null || !winnerMemberIds.contains(member.getId())
                        || occurredAt.isBefore(participant.getEligibleFrom())
                        || processedSources.existsByEventIdAndParticipantIdAndSourceTypeAndSourceId(
                        event.getId(), participant.getId(), BingoProgressSourceType.KILL_COMPETITION, sourceId)) {
                    continue;
                }
                BingoParticipant locked = participants.findForUpdate(participant.getId()).orElseThrow();
                List<BingoProgress> rows = progress.findByParticipantIdOrderByCellPositionAsc(locked.getId());
                boolean applied = false;
                for (BingoProgress row : rows) {
                    if (row.getCell().getMissionType() != BingoMissionType.KILL_BET_WIN) continue;
                    BigDecimal next = row.getCurrentValue().add(BigDecimal.ONE);
                    row.apply(next, row.getOccurrenceCount(), next.compareTo(row.getCell().getTargetValue()) >= 0,
                            null, occurredAt, completedAt);
                    applied = true;
                }
                if (!applied) continue;
                processedSources.save(new BingoProcessedSource(event, locked,
                        BingoProgressSourceType.KILL_COMPETITION, sourceId, occurredAt, completedAt));
                completions.updateLines(event, locked, rows, occurredAt);
            }
        }
    }
}
