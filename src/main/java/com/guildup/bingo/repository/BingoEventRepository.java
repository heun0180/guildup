package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface BingoEventRepository extends JpaRepository<BingoEvent, Long> {
    @EntityGraph(attributePaths = "cells")
    List<BingoEvent> findByCommunityIdOrderByStartsAtDesc(Long communityId);

    @EntityGraph(attributePaths = "cells")
    Optional<BingoEvent> findWithCellsById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "cells")
    @Query("select event from BingoEvent event where event.id = :id")
    Optional<BingoEvent> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from BingoEvent event where event.community.id = :communityId order by event.startsAt asc, event.id asc")
    List<BingoEvent> findByCommunityIdForUpdate(@Param("communityId") Long communityId);
}
