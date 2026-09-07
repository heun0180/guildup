package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 커뮤니티 클랜원의 수동 등록과 목록 조회를 담당한다. */
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

    /** 커뮤니티 존재 여부를 확인하고 외부 계정 연결이 없는 수동 멤버를 저장한다. */
    @Transactional
    public CommunityMember addMember(Long communityId, String nickname) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new IllegalArgumentException("Community not found: " + communityId));

        CommunityMember member = new CommunityMember(community, nickname);
        return communityMemberRepository.save(member);
    }

    /** 커뮤니티 멤버를 등록 순서대로 읽기 전용 조회한다. */
    @Transactional(readOnly = true)
    public List<CommunityMember> getMembers(Long communityId) {
        return communityMemberRepository.findByCommunityIdOrderByIdAsc(communityId);
    }
}
