package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Discord 멤버 한 명 또는 전체 snapshot을 같은 ACTIVE/LEFT 정책으로 DB에 반영한다. */
@Service
public class CommunityMemberDiscordStateService {

    private static final Logger log = LoggerFactory.getLogger(CommunityMemberDiscordStateService.class);

    private final DiscordCommunityConnectionRepository connectionRepository;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final DiscordMemberService discordMemberService;

    public CommunityMemberDiscordStateService(
            DiscordCommunityConnectionRepository connectionRepository,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            DiscordMemberService discordMemberService
    ) {
        this.connectionRepository = connectionRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.discordMemberService = discordMemberService;
    }

    /** 현재 역할 전체를 기준으로 한 사용자만 활성화하거나 LEFT 처리한다. */
    @Transactional
    public MemberSyncOutcome synchronizeMember(
            Long communityId,
            Member discordMember,
            Set<String> configuredRoleIds,
            Instant synchronizedAt
    ) {
        if (discordMember.getUser().isBot()) return MemberSyncOutcome.IGNORED;

        Community community = connectionRepository.findByCommunityId(communityId)
                .orElseThrow(() -> new DiscordCommunityConnectionNotFoundException(communityId))
                .getCommunity();
        String discordUserId = discordMember.getUser().getId();
        CommunityMemberAccount storedAccount = accountRepository
                .findByCommunityIdAndProviderAndExternalUserId(
                        communityId, ExternalAccountProvider.DISCORD, discordUserId
                ).orElse(null);

        if (!hasConfiguredRole(discordMember, configuredRoleIds)) {
            if (storedAccount != null && storedAccount.getCommunityMember().markLeft(synchronizedAt)) {
                log.debug("Discord member marked LEFT - communityId: {}, discordUserId: {}",
                        communityId, discordUserId);
                return MemberSyncOutcome.LEFT;
            }
            return MemberSyncOutcome.IGNORED;
        }

        List<PendingMember> pendingMembers = new ArrayList<>(1);
        MemberSyncOutcome outcome = applyActiveMember(
                community, discordMember, storedAccount, synchronizedAt, pendingMembers
        );
        savePendingMembers(pendingMembers);
        log.debug("Discord member activated - communityId: {}, discordUserId: {}, outcome: {}",
                communityId, discordUserId, outcome);
        return outcome;
    }

    /** Discord 서버 탈퇴는 Discord 계정으로 연결된 멤버만 LEFT 처리한다. */
    @Transactional
    public MemberSyncOutcome markMemberLeft(Long communityId, String discordUserId, Instant synchronizedAt) {
        return accountRepository.findByCommunityIdAndProviderAndExternalUserId(
                        communityId, ExternalAccountProvider.DISCORD, discordUserId
                )
                .filter(account -> account.getCommunityMember().markLeft(synchronizedAt))
                .map(ignored -> {
                    log.debug("Discord member marked LEFT after guild leave - communityId: {}, discordUserId: {}",
                            communityId, discordUserId);
                    return MemberSyncOutcome.LEFT;
                })
                .orElse(MemberSyncOutcome.IGNORED);
    }

    /** 전체 snapshot을 비교하며 수동 멤버에는 손대지 않고 완료 시각만 connection에 기록한다. */
    @Transactional
    public CommunityMemberSyncResponse reconcileSnapshot(
            Long communityId,
            List<Member> discordMembers,
            Set<String> configuredRoleIds,
            Instant synchronizedAt
    ) {
        var connection = connectionRepository.findByCommunityId(communityId)
                .orElseThrow(() -> new DiscordCommunityConnectionNotFoundException(communityId));
        Community community = connection.getCommunity();
        List<Member> humanMembers = discordMembers.stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
        Map<String, Member> matchedMembers = humanMembers.stream()
                .filter(member -> hasConfiguredRole(member, configuredRoleIds))
                .collect(Collectors.toMap(
                        member -> member.getUser().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, CommunityMemberAccount> storedByDiscordUserId = accountRepository
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.DISCORD).stream()
                .collect(Collectors.toMap(CommunityMemberAccount::getExternalUserId, Function.identity()));

        int created = 0;
        int updated = 0;
        int reactivated = 0;
        List<PendingMember> pendingMembers = new ArrayList<>();
        for (Member discordMember : matchedMembers.values()) {
            MemberSyncOutcome outcome = applyActiveMember(
                    community,
                    discordMember,
                    storedByDiscordUserId.get(discordMember.getUser().getId()),
                    synchronizedAt,
                    pendingMembers
            );
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case REACTIVATED -> reactivated++;
                default -> { }
            }
        }
        savePendingMembers(pendingMembers);

        int left = 0;
        for (CommunityMemberAccount storedAccount : storedByDiscordUserId.values()) {
            if (!matchedMembers.containsKey(storedAccount.getExternalUserId())
                    && storedAccount.getCommunityMember().markLeft(synchronizedAt)) {
                left++;
            }
        }
        connection.markMembersSynced(synchronizedAt);
        return new CommunityMemberSyncResponse(
                humanMembers.size(), matchedMembers.size(), created, updated, reactivated, left, synchronizedAt
        );
    }

    private MemberSyncOutcome applyActiveMember(
            Community community,
            Member discordMember,
            CommunityMemberAccount storedAccount,
            Instant synchronizedAt,
            List<PendingMember> pendingMembers
    ) {
        if (storedAccount == null) {
            CommunityMember member = new CommunityMember(
                    community, discordMemberService.getDisplayName(discordMember)
            );
            pendingMembers.add(new PendingMember(member, discordMember));
            return MemberSyncOutcome.CREATED;
        }

        CommunityMember stored = storedAccount.getCommunityMember();
        boolean wasLeft = stored.getStatus() == CommunityMemberStatus.LEFT;
        stored.synchronizeDiscordProfile(discordMemberService.getDisplayName(discordMember), synchronizedAt);
        storedAccount.synchronizeExternalProfile(
                discordMember.getUser().getName(),
                discordMemberService.getDisplayName(discordMember),
                getJoinedAt(discordMember)
        );
        return wasLeft ? MemberSyncOutcome.REACTIVATED : MemberSyncOutcome.UPDATED;
    }

    private void savePendingMembers(List<PendingMember> pendingMembers) {
        if (pendingMembers.isEmpty()) return;

        List<CommunityMember> savedMembers = memberRepository.saveAll(
                pendingMembers.stream().map(PendingMember::member).toList()
        );
        List<CommunityMemberAccount> accounts = new ArrayList<>(savedMembers.size());
        for (int index = 0; index < savedMembers.size(); index++) {
            Member discordMember = pendingMembers.get(index).discordMember();
            accounts.add(new CommunityMemberAccount(
                    savedMembers.get(index),
                    ExternalAccountProvider.DISCORD,
                    discordMember.getUser().getId(),
                    discordMember.getUser().getName(),
                    discordMemberService.getDisplayName(discordMember),
                    getJoinedAt(discordMember)
            ));
        }
        accountRepository.saveAll(accounts);
    }

    private boolean hasConfiguredRole(Member member, Set<String> configuredRoleIds) {
        return member.getRoles().stream().anyMatch(role -> configuredRoleIds.contains(role.getId()));
    }

    private Instant getJoinedAt(Member member) {
        return member.hasTimeJoined() ? member.getTimeJoined().toInstant() : null;
    }

    private record PendingMember(CommunityMember member, Member discordMember) { }

    public enum MemberSyncOutcome {
        CREATED,
        UPDATED,
        REACTIVATED,
        LEFT,
        IGNORED
    }
}
