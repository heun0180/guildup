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

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "user")
    List<CommunityUser> findByCommunityIdOrderByIdAsc(Long communityId);

    Optional<CommunityUser> findByCommunityIdAndUserId(Long communityId, Long userId);

    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    long countByCommunityId(Long communityId);

    interface MemberCount {
        Long getCommunityId();
        Long getMemberCount();
    }

    @org.springframework.data.jpa.repository.Query("select member.community.id as communityId, count(member) as memberCount "
            + "from CommunityUser member where member.community.id in :ids group by member.community.id")
    List<MemberCount> countMembersByCommunityIds(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
}
