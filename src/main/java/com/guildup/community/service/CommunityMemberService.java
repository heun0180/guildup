package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityMemberResponse;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 커뮤니티 클랜원의 수동 등록과 목록 조회를 담당한다. */
@Service
public class CommunityMemberService {

    private final CommunityMemberRepository communityMemberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final CommunityAccessService accessService;

    public CommunityMemberService(
            CommunityMemberRepository communityMemberRepository,
            CommunityMemberAccountRepository accountRepository,
            CommunityAccessService accessService
    ) {
        this.communityMemberRepository = communityMemberRepository;
        this.accountRepository = accountRepository;
        this.accessService = accessService;
    }

    /** 커뮤니티 존재 여부를 확인하고 외부 계정 연결이 없는 수동 멤버를 저장한다. */
    @Transactional
    public CommunityMember addMember(Long userId, Long communityId, String nickname) {
        Community community = accessService.requireManagementAccess(userId, communityId).getCommunity();

        CommunityMember member = new CommunityMember(community, nickname);
        return communityMemberRepository.save(member);
    }

    /** 현재 ACTIVE인 멤버와 Discord 계정을 각각 한 번 조회해 DTO로 반환한다. */
    @Transactional(readOnly = true)
    public List<CommunityMemberResponse> getMembers(Long communityId) {
        List<CommunityMember> members = communityMemberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId,
                CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMemberAccount> discordAccountsByMemberId = accountRepository
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.DISCORD).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(),
                        Function.identity()
                ));
        return members.stream()
                .map(member -> CommunityMemberResponse.from(
                        member,
                        discordAccountsByMemberId.get(member.getId())
                ))
                .toList();
    }
}
