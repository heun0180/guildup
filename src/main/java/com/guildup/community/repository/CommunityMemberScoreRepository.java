package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunityMemberScoreRepository extends JpaRepository<CommunityMemberScore, Long> {
    Optional<CommunityMemberScore> findByCommunityMemberId(Long memberId);
}
