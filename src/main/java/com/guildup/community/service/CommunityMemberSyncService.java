package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.exception.CommunityMemberRoleSettingRequiredException;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Member;
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

/** Discord 역할 조건과 GuildUp 클랜원 행을 비교해 상태와 프로필을 동기화한다. */
@Service
public class CommunityMemberSyncService {

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final CommunityMemberRoleSettingRepository roleSettingRepository;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final DiscordGuildService discordGuildService;
    private final DiscordMemberService discordMemberService;

    public CommunityMemberSyncService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            CommunityMemberRoleSettingRepository roleSettingRepository,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            DiscordGuildService discordGuildService,
            DiscordMemberService discordMemberService
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.roleSettingRepository = roleSettingRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.discordGuildService = discordGuildService;
        this.discordMemberService = discordMemberService;
    }

    /** Discord, 역할 설정, 기존 멤버를 각각 한 번 읽고 메모리에서 비교한다. */
    @Transactional
    public CommunityMemberSyncResponse synchronize(Long userId, Long communityId) {
        Community community = accessService.requireManagementAccess(userId, communityId).getCommunity();
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        Set<String> configuredRoleIds = getConfiguredRoleIds(communityId);

        var guild = discordGuildService.getGuildById(connection.getDiscordGuildId());
        List<Member> discordMembers = discordMemberService.getMembers(guild).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
        Map<String, Member> matchedMembers = discordMembers.stream()
                .filter(member -> hasConfiguredRole(member, configuredRoleIds))
                .collect(Collectors.toMap(
                        member -> member.getUser().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        List<CommunityMemberAccount> storedAccounts = accountRepository.findByCommunityIdAndProvider(
                communityId,
                ExternalAccountProvider.DISCORD
        );
        Map<String, CommunityMemberAccount> storedByDiscordUserId = storedAccounts.stream()
                .collect(Collectors.toMap(CommunityMemberAccount::getExternalUserId, Function.identity()));

        Instant synchronizedAt = Instant.now();
        int created = 0;
        int updated = 0;
        int reactivated = 0;
        List<CommunityMember> newMembers = new ArrayList<>();
        List<Member> newDiscordMembers = new ArrayList<>();

        for (Member discordMember : matchedMembers.values()) {
            String discordUserId = discordMember.getUser().getId();
            CommunityMemberAccount storedAccount = storedByDiscordUserId.get(discordUserId);
            if (storedAccount == null) {
                newMembers.add(new CommunityMember(
                        community,
                        discordMemberService.getDisplayName(discordMember)
                ));
                newDiscordMembers.add(discordMember);
                created++;
                continue;
            }

            CommunityMember stored = storedAccount.getCommunityMember();
            boolean wasLeft = stored.getStatus() == CommunityMemberStatus.LEFT;
            stored.synchronizeDiscordProfile(
                    discordMemberService.getDisplayName(discordMember),
                    synchronizedAt
            );
            storedAccount.synchronizeExternalProfile(
                    discordMember.getUser().getName(),
                    discordMemberService.getDisplayName(discordMember),
                    getJoinedAt(discordMember)
            );
            if (wasLeft) {
                reactivated++;
            } else {
                updated++;
            }
        }

        if (!newMembers.isEmpty()) {
            List<CommunityMember> savedMembers = memberRepository.saveAll(newMembers);
            List<CommunityMemberAccount> newAccounts = new ArrayList<>();
            for (int index = 0; index < savedMembers.size(); index++) {
                Member discordMember = newDiscordMembers.get(index);
                newAccounts.add(new CommunityMemberAccount(
                        savedMembers.get(index),
                        ExternalAccountProvider.DISCORD,
                        discordMember.getUser().getId(),
                        discordMember.getUser().getName(),
                        discordMemberService.getDisplayName(discordMember),
                        getJoinedAt(discordMember)
                ));
            }
            accountRepository.saveAll(newAccounts);
        }

        int left = 0;
        for (CommunityMemberAccount storedAccount : storedByDiscordUserId.values()) {
            if (!matchedMembers.containsKey(storedAccount.getExternalUserId())
                    && storedAccount.getCommunityMember().markLeft(synchronizedAt)) {
                left++;
            }
        }

        connection.markMembersSynced(synchronizedAt);
        return new CommunityMemberSyncResponse(
                discordMembers.size(),
                matchedMembers.size(),
                created,
                updated,
                reactivated,
                left,
                synchronizedAt
        );
    }

    private Set<String> getConfiguredRoleIds(Long communityId) {
        Set<String> roleIds = roleSettingRepository.findByCommunityIdOrderByIdAsc(communityId).stream()
                .map(setting -> setting.getDiscordRoleId())
                .collect(Collectors.toUnmodifiableSet());
        if (roleIds.isEmpty()) {
            throw new CommunityMemberRoleSettingRequiredException();
        }
        return roleIds;
    }

    private boolean hasConfiguredRole(Member member, Set<String> configuredRoleIds) {
        return member.getRoles().stream()
                .map(role -> role.getId())
                .anyMatch(configuredRoleIds::contains);
    }

    private Instant getJoinedAt(Member member) {
        return member.hasTimeJoined() ? member.getTimeJoined().toInstant() : null;
    }
}
