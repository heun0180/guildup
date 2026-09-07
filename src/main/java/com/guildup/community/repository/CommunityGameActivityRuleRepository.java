package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGameActivityRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 커뮤니티 게임별 활동 규칙을 저장하고 조회한다. */
public interface CommunityGameActivityRuleRepository
        extends JpaRepository<CommunityGameActivityRule, Long> {

    Optional<CommunityGameActivityRule> findByCommunityGameId(Long communityGameId);
}
