package com.guildup.community.repository;

import com.guildup.community.domain.CommunityUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** GuildUp 사용자와 커뮤니티의 관리 관계를 저장하고 조회한다. */
public interface CommunityUserRepository extends JpaRepository<CommunityUser, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "community")
    @org.springframework.data.jpa.repository.Query("select m from CommunityUser m where m.user.id = :userId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE order by m.community.id")
    List<CommunityUser> findByUserIdOrderByCommunityIdAsc(Long userId);

    @org.springframework.data.jpa.repository.Query("select m from CommunityUser m where m.community.id = :communityId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE")
    List<CommunityUser> findByCommunityId(Long communityId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "user")
    @org.springframework.data.jpa.repository.Query("select m from CommunityUser m where m.community.id = :communityId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE order by m.id")
    List<CommunityUser> findByCommunityIdOrderByIdAsc(Long communityId);

    @org.springframework.data.jpa.repository.Query("select m from CommunityUser m where m.community.id = :communityId and m.user.id = :userId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE")
    Optional<CommunityUser> findByCommunityIdAndUserId(Long communityId, Long userId);

    @org.springframework.data.jpa.repository.Query("select (count(m) > 0) from CommunityUser m where m.community.id = :communityId and m.user.id = :userId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE")
    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    @org.springframework.data.jpa.repository.Query("select count(m) from CommunityUser m where m.community.id = :communityId and m.endedAt is null and m.user.status = com.guildup.user.domain.UserStatus.ACTIVE")
    long countByCommunityId(Long communityId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from CommunityUser m where m.user.id = :userId and m.endedAt is null order by m.community.id")
    List<CommunityUser> findActiveForWithdrawal(Long userId);

    interface MemberCount {
        Long getCommunityId();
        Long getMemberCount();
    }

    @org.springframework.data.jpa.repository.Query("select member.community.id as communityId, count(member) as memberCount "
            + "from CommunityUser member where member.community.id in :ids and member.endedAt is null and member.user.status = com.guildup.user.domain.UserStatus.ACTIVE group by member.community.id")
    List<MemberCount> countMembersByCommunityIds(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
}
