package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    List<CommunityMember> findByCommunityIdOrderByIdAsc(Long communityId);

    List<CommunityMember> findByCommunityIdAndDiscordUserIdIsNotNull(Long communityId);

    long countByCommunityId(Long communityId);
}
