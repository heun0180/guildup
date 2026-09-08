package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.GameType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 커뮤니티와 게임별 Discord 닉네임 추출 규칙을 저장한다. */
public interface CommunityGameNicknameRuleRepository
        extends JpaRepository<CommunityGameNicknameRule, Long> {

    Optional<CommunityGameNicknameRule> findByCommunityIdAndGameType(Long communityId, GameType gameType);

    boolean existsByCommunityIdAndGameType(Long communityId, GameType gameType);
}
