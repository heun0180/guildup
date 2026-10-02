package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityMemberResponse;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 커뮤니티 클랜원의 수동 등록, 삭제와 목록 조회를 담당한다. */
@Service
public class CommunityMemberService {

    private final CommunityMemberRepository communityMemberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final CommunityAccessService accessService;
    private final Clock clock;

    public CommunityMemberService(
            CommunityMemberRepository communityMemberRepository,
            CommunityMemberAccountRepository accountRepository,
            CommunityAccessService accessService,
            Clock clock
    ) {
        this.communityMemberRepository = communityMemberRepository;
        this.accountRepository = accountRepository;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** 커뮤니티 존재 여부를 확인하고 외부 계정 연결이 없는 수동 멤버를 저장한다. */
    @Transactional
    public CommunityMember addMember(Long userId, Long communityId, String nickname) {
        Community community = accessService.requireManagementAccess(userId, communityId).getCommunity();

        CommunityMember member = new CommunityMember(community, nickname);
        return communityMemberRepository.save(member);
    }

    /** 수동 클랜원을 LEFT로 전환해 목록에서 제외하고 기존 활동과 점수 기록은 보존한다. */
    @Transactional
    public void deleteMember(Long userId, Long communityId, Long memberId) {
        accessService.requireManagementAccess(userId, communityId);
        CommunityMember member = communityMemberRepository.findAnyForUpdate(communityId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "클랜원을 찾을 수 없습니다."));
        if (accountRepository.findByCommunityMemberIdAndProvider(memberId, ExternalAccountProvider.DISCORD)
                .isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "수동 등록한 클랜원만 삭제할 수 있습니다. Discord 클랜원은 역할 설정으로 관리해 주세요.");
        }
        member.markLeft(clock.instant());
    }

    /** 현재 ACTIVE인 멤버와 Discord 계정을 각각 한 번 조회해 DTO로 반환한다. */
    @Transactional(readOnly = true)
    public List<CommunityMemberResponse> getMembers(Long userId, Long communityId) {
        accessService.requireCommunityMember(userId, communityId);
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
        Map<Long, CommunityMemberAccount> gameAccountsByMemberId = accountRepository
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.PUBG).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(),
                        Function.identity()
                ));
        return members.stream()
                .map(member -> CommunityMemberResponse.from(
                        member,
                        discordAccountsByMemberId.get(member.getId()),
                        gameAccountsByMemberId.get(member.getId())
                ))
                .toList();
    }
}
