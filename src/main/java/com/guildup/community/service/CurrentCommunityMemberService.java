package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.user.repository.UserExternalAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/** GuildUp 멤버십의 내부 클랜원 연결을 우선 사용하고 기존 Discord 계정 매칭도 지원한다. */
@Service
@Transactional(readOnly = true)
public class CurrentCommunityMemberService {
    private final CommunityAccessService access;
    private final UserExternalAccountRepository userAccounts;
    private final CommunityMemberAccountRepository memberAccounts;
    private final CommunityMemberRepository members;

    public CurrentCommunityMemberService(CommunityAccessService access,
                                         UserExternalAccountRepository userAccounts,
                                         CommunityMemberAccountRepository memberAccounts,
                                         CommunityMemberRepository members) {
        this.access = access;
        this.userAccounts = userAccounts;
        this.memberAccounts = memberAccounts;
        this.members = members;
    }

    public Optional<CommunityMember> find(Long userId, Long communityId) {
        var nativeMember = access.requireCommunityMember(userId, communityId).getCommunityMember();
        if (nativeMember != null) return Optional.of(nativeMember).filter(member -> member.getStatus() == CommunityMemberStatus.ACTIVE);
        return findWithoutAccessCheck(userId, communityId);
    }

    public CommunityMember require(Long userId, Long communityId) {
        return find(userId, communityId).orElseThrow(this::memberLinkRequired);
    }

    public CommunityMember requireForUpdate(Long userId, Long communityId) {
        CommunityMember member = find(userId, communityId)
                .orElseThrow(this::memberLinkRequired);
        return members.findForUpdate(communityId, member.getId(), CommunityMemberStatus.ACTIVE)
                .orElseThrow(this::memberLinkRequired);
    }

    private Optional<CommunityMember> findWithoutAccessCheck(Long userId, Long communityId) {
        return userAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD)
                .flatMap(userAccount -> memberAccounts
                        .findByCommunityIdAndProviderAndExternalUserId(
                                communityId,
                                ExternalAccountProvider.DISCORD,
                                userAccount.getExternalUserId()
                        ))
                .map(account -> account.getCommunityMember())
                .filter(member -> member.getStatus() == CommunityMemberStatus.ACTIVE);
    }

    private ResponseStatusException memberLinkRequired() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "로그인 사용자와 연결된 활성 클랜원이 없습니다. 커뮤니티의 클랜원 연결을 확인해 주세요.");
    }
}
