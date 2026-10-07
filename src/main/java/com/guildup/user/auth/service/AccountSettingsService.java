package com.guildup.user.auth.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.user.auth.dto.LoginUserResponse;
import com.guildup.user.repository.UserCredentialRepository;
import com.guildup.user.repository.UserExternalAccountRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountSettingsService {
    private final AuthSessionService sessions;
    private final UserCredentialRepository credentials;
    private final UserExternalAccountRepository externalAccounts;
    private final CommunityUserRepository memberships;
    private final CommunityMemberAccountRepository memberAccounts;

    public AccountSettingsService(AuthSessionService sessions, UserCredentialRepository credentials,
                                  UserExternalAccountRepository externalAccounts, CommunityUserRepository memberships,
                                  CommunityMemberAccountRepository memberAccounts) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.externalAccounts = externalAccounts;
        this.memberships = memberships;
        this.memberAccounts = memberAccounts;
    }

    @Transactional(readOnly = true)
    public AccountResponse get(HttpSession session) {
        var user = sessions.requireUser(session);
        var credential = credentials.findByUserId(user.getId());
        var discord = externalAccounts.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD);
        List<MemberLinkConflict> conflicts = discord.map(account -> memberships.findByUserIdOrderByCommunityIdAsc(user.getId())
                .stream().filter(membership -> membership.getCommunityMember() != null)
                .filter(membership -> memberAccounts.findByCommunityIdAndProviderAndExternalUserId(
                        membership.getCommunity().getId(), ExternalAccountProvider.DISCORD, account.getExternalUserId())
                        .map(existing -> !existing.getCommunityMember().getId().equals(membership.getCommunityMember().getId()))
                        .orElse(false))
                .map(membership -> new MemberLinkConflict(membership.getCommunity().getId(), membership.getCommunity().getName()))
                .toList()).orElse(List.of());
        return new AccountResponse(LoginUserResponse.from(user), credential.map(value -> value.getEmail()).orElse(null),
                credential.map(value -> value.isEmailVerified()).orElse(false), discord.isPresent(),
                discord.map(value -> value.getExternalUsername()).orElse(null), conflicts);
    }

    public record AccountResponse(LoginUserResponse user, String email, boolean emailVerified,
                                  boolean discordConnected, String discordUsername,
                                  List<MemberLinkConflict> memberLinkConflicts) {}
    public record MemberLinkConflict(Long communityId, String communityName) {}
}
