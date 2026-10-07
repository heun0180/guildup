package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.user.repository.UserExternalAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Discord 없이 가입한 사용자도 출석/게임 참여에 사용할 내부 클랜원을 갖게 한다. */
@Service
public class CommunityNativeMembershipService {
    private final CommunityMemberRepository members;
    private final CommunityMemberAccountRepository accounts;
    private final UserExternalAccountRepository userAccounts;
    private final CommunityUserRepository memberships;

    public CommunityNativeMembershipService(CommunityMemberRepository members, CommunityMemberAccountRepository accounts,
                                            UserExternalAccountRepository userAccounts, CommunityUserRepository memberships) {
        this.members = members;
        this.accounts = accounts;
        this.userAccounts = userAccounts;
        this.memberships = memberships;
    }

    /** 호출자는 생성/가입 트랜잭션과 사용자 행 잠금을 유지한다. */
    @Transactional
    public void provisionIfDiscordAbsent(CommunityUser membership) {
        if (userAccounts.findByUserIdAndProvider(membership.getUser().getId(), ExternalAccountProvider.DISCORD).isEmpty()) {
            provision(membership);
        }
    }

    /** 기존 community_member_id는 교체하지 않는다. */
    @Transactional
    public void provision(CommunityUser membership) {
        if (membership.getCommunityMember() != null) return;
        var discordAccount = userAccounts.findByUserIdAndProvider(membership.getUser().getId(), ExternalAccountProvider.DISCORD);
        var existing = discordAccount.flatMap(account -> accounts.findByCommunityIdAndProviderAndExternalUserId(
                membership.getCommunity().getId(), ExternalAccountProvider.DISCORD, account.getExternalUserId()));
        CommunityMember member = existing.map(CommunityMemberAccount::getCommunityMember)
                .orElseGet(() -> members.save(new CommunityMember(membership.getCommunity(), membership.getUser().getNickname())));
        // 계정이 이미 있다면 식별자만 연결한다. 이후 서버 동기화가 같은 멤버를 재사용해 기록을 보존한다.
        if (existing.isEmpty()) discordAccount.ifPresent(account -> accounts.save(new CommunityMemberAccount(
                member, ExternalAccountProvider.DISCORD, account.getExternalUserId(), account.getExternalUsername())));
        membership.linkCommunityMember(member);
        memberships.save(membership);
    }
}
