package com.guildup.bingo.dto;

import com.guildup.bingo.domain.BingoEvent;
import java.time.Instant;

public record BingoSummaryResponse(Long id, String title, String description, int boardSize,
                                   int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
                                   Instant startsAt, Instant endsAt, String status,
                                   int participantCount, Integer myCompletedCells, Integer myLineCount) {
    public static BingoSummaryResponse from(BingoEvent event, int participants, Integer completed, Integer lines) {
        return new BingoSummaryResponse(event.getId(), event.getTitle(), event.getDescription(), event.getBoardSize(),
                event.getTargetLines(), event.isBlackoutEnabled(), event.isAllowLateJoin(), event.getStartsAt(),
                event.getEndsAt(), event.getStatus().name(), participants, completed, lines);
    }
}
