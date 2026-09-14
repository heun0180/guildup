package com.guildup.killcompetition.repository;

import com.guildup.killcompetition.domain.KillCompetitionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface KillCompetitionParticipantRepository extends JpaRepository<KillCompetitionParticipant, Long> {
    Optional<KillCompetitionParticipant> findByCompetitionIdAndCommunityMemberId(Long competitionId, Long memberId);
    boolean existsByCompetitionIdAndCommunityMemberId(Long competitionId, Long memberId);
}
