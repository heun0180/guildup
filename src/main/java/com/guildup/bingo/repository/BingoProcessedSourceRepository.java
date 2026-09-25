package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoProcessedSource;
import com.guildup.bingo.domain.BingoProgressSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BingoProcessedSourceRepository extends JpaRepository<BingoProcessedSource, Long> {
    boolean existsByEventIdAndParticipantIdAndSourceTypeAndSourceId(
            Long eventId, Long participantId, BingoProgressSourceType sourceType, String sourceId);
    List<BingoProcessedSource> findBySourceTypeAndSourceId(
            BingoProgressSourceType sourceType, String sourceId);
    List<BingoProcessedSource> findByEventIdAndParticipantIdAndSourceTypeOrderByOccurredAtAscIdAsc(
            Long eventId, Long participantId, BingoProgressSourceType sourceType);
}
