package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.mission.BingoLineCalculator;
import com.guildup.bingo.repository.BingoLineCompletionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

    /** 진행률이 감소할 수 있는 삭제/재집계 뒤 파생 줄 상태를 정확히 다시 만든다. */
    public void rebuildLines(BingoEvent event, BingoParticipant participant,
                             List<BingoProgress> rows, Instant fallback) {
        Map<Integer, Instant> completedAtByPosition = new HashMap<>();
        rows.stream().filter(BingoProgress::isCompleted).forEach(row -> completedAtByPosition.put(
                row.getCell().getPosition(), Optional.ofNullable(row.getCompletedAt()).orElse(fallback)));
        Set<String> completed = lineCalculator.completedLines(event.getBoardSize(), completedAtByPosition.keySet());
        Map<String, Instant> expected = new LinkedHashMap<>();
        for (String key : completed) {
            Instant at = linePositions(key, event.getBoardSize()).stream().map(completedAtByPosition::get)
                    .max(Comparator.naturalOrder()).orElse(fallback);
            expected.put(key, at);
        }
        Map<String, BingoLineCompletion> current = lineCompletions.findByParticipantId(participant.getId()).stream()
                .collect(Collectors.toMap(BingoLineCompletion::getLineKey, row -> row));
        List<BingoLineCompletion> obsolete = current.entrySet().stream()
                .filter(entry -> !expected.containsKey(entry.getKey())).map(Map.Entry::getValue).toList();
        if (!obsolete.isEmpty()) lineCompletions.deleteAllInBatch(obsolete);
        expected.forEach((key, at) -> {
            BingoLineCompletion row = current.get(key);
            if (row == null) lineCompletions.save(new BingoLineCompletion(participant, key, at));
            else row.repairCompletedAt(at);
        });
        List<Instant> lineTimes = new ArrayList<>(expected.values());
        lineTimes.sort(Comparator.naturalOrder());
        Instant targetAt = lineTimes.size() >= event.getTargetLines()
                ? lineTimes.get(event.getTargetLines() - 1) : null;
        Instant blackoutAt = event.isBlackoutEnabled() && completedAtByPosition.size() == event.getCells().size()
                ? completedAtByPosition.values().stream().max(Comparator.naturalOrder()).orElse(fallback) : null;
        participant.replaceDerivedProgress(completed.size(), targetAt, blackoutAt);
    }

    private Set<Integer> linePositions(String key, int size) {
        Set<Integer> positions = new java.util.LinkedHashSet<>();
        if (key.startsWith("ROW_")) {
            int row = Integer.parseInt(key.substring(4));
            for (int col = 0; col < size; col++) positions.add(row * size + col);
        } else if (key.startsWith("COL_")) {
            int col = Integer.parseInt(key.substring(4));
            for (int row = 0; row < size; row++) positions.add(row * size + col);
        } else if ("DIAG_MAIN".equals(key)) {
            for (int index = 0; index < size; index++) positions.add(index * size + index);
        } else if ("DIAG_ANTI".equals(key)) {
            for (int index = 0; index < size; index++) positions.add(index * size + (size - index - 1));
        }
        return positions;
    }
}
