package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CommunityMemberService {

    private final CommunityRepository communityRepository;
    private final CommunityMemberRepository communityMemberRepository;

    public CommunityMemberService(
            CommunityRepository communityRepository,
            CommunityMemberRepository communityMemberRepository
    ) {
        this.communityRepository = communityRepository;
        this.communityMemberRepository = communityMemberRepository;
    }

    @Transactional
    public CommunityMember addMember(Long communityId, String nickname) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new IllegalArgumentException("Community not found: " + communityId));

        CommunityMember member = new CommunityMember(community, nickname);
        return communityMemberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public List<CommunityMember> getMembers(Long communityId) {
        return communityMemberRepository.findByCommunityIdOrderByIdAsc(communityId);
    }
}
