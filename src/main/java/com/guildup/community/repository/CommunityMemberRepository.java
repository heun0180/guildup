package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/** GuildUp이 자체 관리하는 커뮤니티 클랜원의 저장과 조회를 담당한다. */
public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    /** 화면 표시를 위해 해당 커뮤니티 멤버를 등록 순서대로 조회한다. */
    List<CommunityMember> findByCommunityIdOrderByIdAsc(Long communityId);

    /** Members 화면에는 현재 ACTIVE인 클랜원만 반환한다. */
    List<CommunityMember> findByCommunityIdAndStatusOrderByIdAsc(
            Long communityId,
            CommunityMemberStatus status
    );

    /** 출석과 점수 갱신을 같은 멤버 단위로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select member from CommunityMember member
            where member.id = :memberId
              and member.community.id = :communityId
              and member.status = :status
            """)
    Optional<CommunityMember> findForUpdate(
            @Param("communityId") Long communityId,
            @Param("memberId") Long memberId,
            @Param("status") CommunityMemberStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from CommunityMember member where member.id = :memberId and member.community.id = :communityId")
    Optional<CommunityMember> findAnyForUpdate(
            @Param("communityId") Long communityId,
            @Param("memberId") Long memberId
    );

    /** 점수 행이 아직 없는 ACTIVE 멤버도 0점으로 포함하고 순서를 고정한다. */
    @Query("""
            select member, coalesce(score.totalScore, 0)
            from CommunityMember member
            left join CommunityMemberScore score on score.communityMember = member
            where member.community.id = :communityId
              and member.status = :status
            order by coalesce(score.totalScore, 0) desc, member.id asc
            """)
    List<Object[]> findRankingRows(
            @Param("communityId") Long communityId,
            @Param("status") CommunityMemberStatus status
    );
}
