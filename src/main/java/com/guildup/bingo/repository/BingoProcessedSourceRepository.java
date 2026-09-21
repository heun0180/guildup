package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoProcessedSource;
import com.guildup.bingo.domain.BingoProgressSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BingoProcessedSourceRepository extends JpaRepository<BingoProcessedSource, Long> {
    boolean existsByEventIdAndParticipantIdAndSourceTypeAndSourceId(
            Long eventId, Long participantId, BingoProgressSourceType sourceType, String sourceId);
}
