package com.guildup.bingo.repository;

import com.guildup.bingo.domain.BingoParticipant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface BingoParticipantRepository extends JpaRepository<BingoParticipant, Long> {
    @EntityGraph(attributePaths = {"communityUser.user", "communityMember"})
    List<BingoParticipant> findByEventIdOrderByIdAsc(Long eventId);
    @EntityGraph(attributePaths = {"communityUser.user", "communityMember"})
    Optional<BingoParticipant> findByEventIdAndCommunityUserUserId(Long eventId, Long userId);
    boolean existsByEventIdAndCommunityUserId(Long eventId, Long communityUserId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BingoParticipant p where p.id = :id")
    Optional<BingoParticipant> findForUpdate(@Param("id") Long id);
}
