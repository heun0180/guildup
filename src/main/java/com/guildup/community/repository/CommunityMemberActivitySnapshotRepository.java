package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberActivitySnapshot;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommunityMemberActivitySnapshotRepository
        extends JpaRepository<CommunityMemberActivitySnapshot, Long> {

    @EntityGraph(attributePaths = "communityMember")
    List<CommunityMemberActivitySnapshot> findByCommunityGameIdOrderByCommunityMemberIdAsc(
            Long communityGameId
    );

    @EntityGraph(attributePaths = {"communityMember", "matches"})
    Optional<CommunityMemberActivitySnapshot> findByCommunityGameIdAndCommunityMemberId(
            Long communityGameId,
            Long communityMemberId
    );

    void deleteByCommunityGameId(Long communityGameId);
}
