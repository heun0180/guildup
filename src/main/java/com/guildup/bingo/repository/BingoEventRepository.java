package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface BingoEventRepository extends JpaRepository<BingoEvent, Long> {
    boolean existsByIdAndCommunityIdAndCommunityGameId(Long id, Long communityId, Long communityGameId);
    @EntityGraph(attributePaths = "cells")
    List<BingoEvent> findByCommunityGameIdOrderByStartsAtDesc(Long communityGameId);
    List<BingoEvent> findByCommunityIdOrderByStartsAtDesc(Long communityId);

    @EntityGraph(attributePaths = "cells")
    Optional<BingoEvent> findWithCellsById(Long id);

    @EntityGraph(attributePaths = "cells")
    Optional<BingoEvent> findWithCellsByIdAndCommunityGameId(Long id, Long communityGameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "cells")
    @Query("select event from BingoEvent event where event.id = :id")
    Optional<BingoEvent> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from BingoEvent event where event.communityGame.id = :communityGameId order by event.startsAt asc, event.id asc")
    List<BingoEvent> findByCommunityGameIdForUpdate(@Param("communityGameId") Long communityGameId);
}
