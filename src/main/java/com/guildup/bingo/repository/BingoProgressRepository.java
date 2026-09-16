package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoProgress;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface BingoProgressRepository extends JpaRepository<BingoProgress, Long> {
    @EntityGraph(attributePaths = "cell")
    List<BingoProgress> findByParticipantIdOrderByCellPositionAsc(Long participantId);
    Optional<BingoProgress> findByParticipantIdAndCellId(Long participantId, Long cellId);
    @Query("select p from BingoProgress p join fetch p.participant part join fetch part.communityUser cu join fetch cu.user where p.cell.id = :cellId and p.completed = true order by p.completedAt asc")
    List<BingoProgress> findCompletions(Long cellId);
}
