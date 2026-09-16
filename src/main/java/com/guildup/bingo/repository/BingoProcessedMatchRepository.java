package com.guildup.bingo.repository;
import com.guildup.bingo.domain.BingoProcessedMatch;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BingoProcessedMatchRepository extends JpaRepository<BingoProcessedMatch, Long> {
    boolean existsByEventIdAndParticipantIdAndMatchId(Long eventId, Long participantId, String matchId);
}
