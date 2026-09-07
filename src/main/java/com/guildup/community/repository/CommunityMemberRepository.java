package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** GuildUp이 자체 관리하는 커뮤니티 클랜원의 저장과 조회를 담당한다. */
public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    /** 화면 표시를 위해 해당 커뮤니티 멤버를 등록 순서대로 조회한다. */
    List<CommunityMember> findByCommunityIdOrderByIdAsc(Long communityId);

    /** Members 화면에는 현재 ACTIVE인 클랜원만 반환한다. */
    List<CommunityMember> findByCommunityIdAndStatusOrderByIdAsc(
            Long communityId,
            CommunityMemberStatus status
    );
}
