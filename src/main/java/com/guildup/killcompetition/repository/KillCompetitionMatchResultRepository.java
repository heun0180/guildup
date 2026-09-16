package com.guildup.killcompetition.repository;

import com.guildup.killcompetition.domain.KillCompetitionMatchResult;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KillCompetitionMatchResultRepository extends JpaRepository<KillCompetitionMatchResult, Long> {
    @EntityGraph(attributePaths = {"participant", "participant.communityMember"})
    List<KillCompetitionMatchResult> findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(Long competitionId);
    void deleteByCompetitionId(Long competitionId);
    boolean existsByParticipantId(Long participantId);
}
