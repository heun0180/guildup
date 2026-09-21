package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.mission.BingoLineCalculator;
import com.guildup.bingo.repository.BingoLineCompletionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 모든 진행률 원천이 같은 빙고 줄/블랙빙고 완료 정책을 사용하게 한다. */
@Service
public class BingoProgressCompletionService {
    private final BingoLineCompletionRepository lineCompletions;
    private final BingoLineCalculator lineCalculator;

    public BingoProgressCompletionService(BingoLineCompletionRepository lineCompletions,
                                          BingoLineCalculator lineCalculator) {
        this.lineCompletions = lineCompletions;
        this.lineCalculator = lineCalculator;
    }

    public void updateLines(BingoEvent event, BingoParticipant participant,
                            List<BingoProgress> rows, Instant completedAt) {
        Set<Integer> positions = rows.stream().filter(BingoProgress::isCompleted)
                .map(row -> row.getCell().getPosition()).collect(Collectors.toSet());
        Set<String> completed = lineCalculator.completedLines(event.getBoardSize(), positions);
        Set<String> existing = lineCompletions.findByParticipantId(participant.getId()).stream()
                .map(BingoLineCompletion::getLineKey).collect(Collectors.toSet());
        completed.stream().filter(key -> !existing.contains(key))
                .forEach(key -> lineCompletions.save(new BingoLineCompletion(participant, key, completedAt)));
        boolean blackout = rows.size() == event.getBoardSize() * event.getBoardSize()
                && rows.stream().allMatch(BingoProgress::isCompleted);
        participant.updateLines(completed.size(), completed.size() >= event.getTargetLines(),
                event.isBlackoutEnabled() && blackout, completedAt);
    }
}
