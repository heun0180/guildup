package com.guildup.user.auth.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.announcement.repository.PlatformAnnouncementReadRepository;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.user.auth.exception.*;
import com.guildup.user.domain.User;
import com.guildup.user.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

import java.time.Clock;
import java.util.*;

/** 회원탈퇴 전용 DB 경계. 커뮤니티 삭제/탈퇴/이벤트 재정산을 호출하지 않는다. */
@Service
public class AccountWithdrawalService {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final UserExternalAccountRepository externalAccounts;
    private final CommunityUserRepository memberships;
    private final CommunityMemberAccountRepository memberAccounts;
    private final DiscordVoiceSessionRepository voiceSessions;
    private final PlatformAnnouncementReadRepository announcementReads;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final MonitoringEventService monitoring;
    private final com.guildup.discord.oauth.store.DiscordOAuthSessionStore oauthResults;

    public AccountWithdrawalService(UserRepository users, UserCredentialRepository credentials,
            UserExternalAccountRepository externalAccounts, CommunityUserRepository memberships,
            CommunityMemberAccountRepository memberAccounts, DiscordVoiceSessionRepository voiceSessions,
            PlatformAnnouncementReadRepository announcementReads, PasswordEncoder encoder,
            Clock clock, MonitoringEventService monitoring, com.guildup.discord.oauth.store.DiscordOAuthSessionStore oauthResults) {
        this.users = users; this.credentials = credentials; this.externalAccounts = externalAccounts;
        this.memberships = memberships; this.memberAccounts = memberAccounts; this.voiceSessions = voiceSessions;
        this.announcementReads = announcementReads; this.encoder = encoder; this.clock = clock; this.monitoring = monitoring;
        this.oauthResults = oauthResults;
    }

    @Transactional(readOnly = true)
    public CheckResponse check(Long userId, WithdrawalVerification proof) {
        active(userId, false);
        var owned = owned(memberships.findByUserIdOrderByCommunityIdAsc(userId));
        var credential = credentials.findByUserId(userId);
        var discord = externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD);
        var method = credential.isPresent() ? WithdrawalVerification.Method.PASSWORD : WithdrawalVerification.Method.DISCORD;
        Long methodId = credential.map(value -> value.getId()).orElseGet(() -> discord.map(value -> value.getId()).orElse(null));
        boolean verified = methodId != null && proof != null && proof.validFor(userId, method, methodId, clock.instant());
        return new CheckResponse(owned.isEmpty() && methodId != null, methodId == null ? "UNAVAILABLE" : method.name(),
                verified, owned);
    }

    @Transactional(readOnly = true)
    public WithdrawalVerification verifyPassword(Long userId, String password) {
        active(userId, false);
        var credential = credentials.findByUserId(userId).orElseThrow(() -> verificationRequired());
        if (!CredentialPolicy.isEncodablePassword(password) || !encoder.matches(password, credential.getPasswordHash())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
        }
        return new WithdrawalVerification(userId, WithdrawalVerification.Method.PASSWORD, credential.getId(), clock.instant());
    }

    @Transactional(readOnly = true)
    public void requireDiscordVerification(Long userId) {
        active(userId, false);
        if (credentials.existsByUserId(userId)) throw verificationRequired();
        if (externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD).isEmpty()) throw verificationRequired();
    }

    @Transactional(readOnly = true)
    public WithdrawalVerification verifyDiscord(Long userId, DiscordApiUser discord) {
        requireDiscordVerification(userId);
        var account = externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD).orElseThrow(() -> verificationRequired());
        if (!account.getExternalUserId().equals(discord.id())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "DISCORD_IDENTITY_MISMATCH", "현재 계정에 연결된 Discord 계정으로 인증해 주세요.");
        }
        return new WithdrawalVerification(userId, WithdrawalVerification.Method.DISCORD, account.getId(), clock.instant());
    }

    @Transactional
    public void withdraw(Long userId, WithdrawalVerification proof, boolean acknowledged) {
        User user = active(userId, true);
        var links = memberships.findActiveForWithdrawal(userId);
        var owned = owned(links);
        if (!owned.isEmpty()) throw new WithdrawalBlockedException(owned);
        if (!acknowledged) throw new AuthException(HttpStatus.BAD_REQUEST, "WITHDRAWAL_CONFIRMATION_REQUIRED", "회원탈퇴 안내와 최종 확인이 필요합니다.");
        var credential = credentials.findByUserId(userId);
        var discord = externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD);
        var method = credential.isPresent() ? WithdrawalVerification.Method.PASSWORD : WithdrawalVerification.Method.DISCORD;
        Long methodId = credential.map(value -> value.getId()).orElseGet(() -> discord.map(value -> value.getId()).orElse(null));
        if (methodId == null || proof == null || !proof.validFor(userId, method, methodId, clock.instant())) throw verificationRequired();

        var now = clock.instant();
        Map<Long, CommunityMember> retained = new LinkedHashMap<>();
        links.stream().map(CommunityUser::getCommunityMember).filter(Objects::nonNull)
                .forEach(member -> retained.put(member.getId(), member));
        // Discord 서버가 수집한, 아직 GuildUp 멤버십과 연결되지 않은 클랜원 복제도 정리한다.
        discord.ifPresent(account -> memberAccounts.findByProviderAndExternalUserId(ExternalAccountProvider.DISCORD, account.getExternalUserId())
                .forEach(value -> retained.put(value.getCommunityMember().getId(), value.getCommunityMember())));
        for (var member : retained.values()) {
            String surrogate = "withdrawn-member-" + member.getId();
            for (var account : memberAccounts.findByCommunityMemberId(member.getId())) {
                if (account.getProvider() == ExternalAccountProvider.DISCORD) {
                    voiceSessions.findByCommunityIdAndDiscordUserId(member.getCommunity().getId(), account.getExternalUserId())
                            .forEach(voice -> voice.anonymize(surrogate, now));
                }
                account.anonymize(surrogate);
            }
            member.anonymize();
        }
        // 클랜원 레코드가 없는 Discord 음성 기록도 외부 식별자를 남기지 않는다.
        discord.ifPresent(account -> voiceSessions.findByDiscordUserId(account.getExternalUserId())
                .forEach(voice -> voice.anonymize("withdrawn-user-" + userId, now)));
        links.forEach(link -> link.endMembership(now));
        credentials.deleteAll(credentials.findByUserId(userId).stream().toList());
        externalAccounts.deleteAll(externalAccounts.findByUserId(userId));
        announcementReads.deleteByUser_Id(userId);
        user.withdraw(now);
        users.flush();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                discord.ifPresent(account -> oauthResults.discardResultsForDiscordUser(account.getExternalUserId()));
                monitoring.recordInfo(MonitoringCategory.SYSTEM, MonitoringEventCode.USER_WITHDRAWN,
                        "GuildUp account withdrawn", null, userId, "user:" + userId, Map.of());
            }
        });
    }

    private User active(Long id, boolean lock) {
        return (lock ? users.findForUpdate(id) : users.findById(id)).filter(User::isActive).orElseThrow(() ->
                new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."));
    }
    private List<OwnedCommunity> owned(List<CommunityUser> links) {
        return links.stream().filter(link -> link.getRole() == CommunityUserRole.OWNER)
                .map(link -> new OwnedCommunity(link.getCommunity().getId(), link.getCommunity().getName())).toList();
    }
    private AuthException verificationRequired() {
        return new AuthException(HttpStatus.FORBIDDEN, "WITHDRAWAL_VERIFICATION_REQUIRED", "회원탈퇴를 위해 본인 확인을 다시 진행해 주세요.");
    }
    public record OwnedCommunity(Long communityId, String communityName) {}
    public record CheckResponse(boolean canWithdraw, String verificationMethod, boolean verified,
                                List<OwnedCommunity> ownedCommunities) {}
}
