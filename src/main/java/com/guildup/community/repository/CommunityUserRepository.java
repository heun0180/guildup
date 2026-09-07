package com.guildup.community.repository;

import com.guildup.community.domain.CommunityUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** GuildUp 사용자와 커뮤니티의 관리 관계를 저장하고 조회한다. */
public interface CommunityUserRepository extends JpaRepository<CommunityUser, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "community")
    List<CommunityUser> findByUserIdOrderByCommunityIdAsc(Long userId);

    List<CommunityUser> findByCommunityId(Long communityId);

    Optional<CommunityUser> findByCommunityIdAndUserId(Long communityId, Long userId);
}
