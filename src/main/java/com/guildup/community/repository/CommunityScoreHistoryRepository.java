package com.guildup.community.repository;

import com.guildup.community.domain.CommunityScoreHistory;
import com.guildup.community.domain.CommunityScoreType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;

public interface CommunityScoreHistoryRepository extends JpaRepository<CommunityScoreHistory, Long> {
    List<CommunityScoreHistory> findByCommunityMemberIdOrderByCreatedAtDescIdDesc(Long memberId);
    long countByCommunityMemberId(Long memberId);
    boolean existsByCommunityMemberIdAndScoreTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long memberId, CommunityScoreType scoreType, Instant start, Instant end
    );
    boolean existsByCommunityMemberIdAndScoreTypeAndReferenceId(
            Long memberId, CommunityScoreType scoreType, Long referenceId
    );
}
