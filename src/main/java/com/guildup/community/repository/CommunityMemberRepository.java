package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** GuildUp이 자체 관리하는 커뮤니티 클랜원의 저장과 조회를 담당한다. */
public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    /** 화면 표시를 위해 해당 커뮤니티 멤버를 등록 순서대로 조회한다. */
    List<CommunityMember> findByCommunityIdOrderByIdAsc(Long communityId);
}
