package com.guildup.killcompetition.repository;

import com.guildup.killcompetition.domain.KillCompetition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface KillCompetitionRepository extends JpaRepository<KillCompetition, Long> {
    @EntityGraph(attributePaths = {"createdBy", "participants", "participants.communityMember", "participants.team", "teams"})
    List<KillCompetition> findByCommunityIdOrderByCreatedAtDesc(Long communityId);

    @EntityGraph(attributePaths = {"createdBy", "participants", "participants.communityMember", "participants.team", "teams"})
    @Query("select distinct competition from KillCompetition competition where competition.id = :id and competition.community.id = :communityId")
    Optional<KillCompetition> findDetail(@Param("communityId") Long communityId, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select competition from KillCompetition competition where competition.id = :id and competition.community.id = :communityId")
    Optional<KillCompetition> findForUpdate(@Param("communityId") Long communityId, @Param("id") Long id);

    @Query("""
            select competition.id from KillCompetition competition
            where competition.status = com.guildup.killcompetition.domain.KillCompetitionStatus.RESULT_PENDING
              and competition.resultPublishAt <= :now
            order by competition.resultPublishAt, competition.id
            """)
    List<Long> findResultPublishCandidateIds(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select competition from KillCompetition competition where competition.id = :id")
    Optional<KillCompetition> findByIdForUpdate(@Param("id") Long id);
}
