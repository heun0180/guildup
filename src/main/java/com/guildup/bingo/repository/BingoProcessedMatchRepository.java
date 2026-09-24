package com.guildup.bingo.repository;
import com.guildup.bingo.domain.BingoProcessedMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface BingoProcessedMatchRepository extends JpaRepository<BingoProcessedMatch, Long> {
    boolean existsByEventIdAndParticipantIdAndMatchId(Long eventId, Long participantId, String matchId);
    @Query("select processed.matchId from BingoProcessedMatch processed where processed.event.id = :eventId and processed.participant.id = :participantId")
    List<String> findMatchIds(@Param("eventId") Long eventId, @Param("participantId") Long participantId);
    List<BingoProcessedMatch> findByEventId(Long eventId);
    @Query("select distinct processed.matchId from BingoProcessedMatch processed where processed.event.id = :eventId")
    List<String> findDistinctMatchIdsByEventId(@Param("eventId") Long eventId);
}
