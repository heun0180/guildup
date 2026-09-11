package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberRoleSetting;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.exception.CommunityMemberRoleSettingRequiredException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommunityMemberSyncServiceTests {

    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final DiscordCommunityConnectionService connectionService =
            mock(DiscordCommunityConnectionService.class);
    private final CommunityMemberRoleSettingRepository roleSettingRepository =
            mock(CommunityMemberRoleSettingRepository.class);
    private final CommunityMemberRepository memberRepository = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accountRepository =
            mock(CommunityMemberAccountRepository.class);
    private final DiscordGuildService discordGuildService = mock(DiscordGuildService.class);
    private final DiscordMemberService discordMemberService = new DiscordMemberService();
    private final CommunityMemberSyncService syncService = new CommunityMemberSyncService(
            accessService,
            connectionService,
            roleSettingRepository,
            memberRepository,
            accountRepository,
            discordGuildService,
            discordMemberService
    );

    private final Community community = new Community("GuildUp");
    private final Guild guild = mock(Guild.class);

    @BeforeEach
    void setUp() {
        CommunityUser membership = mock(CommunityUser.class);
        when(membership.getCommunity()).thenReturn(community);
        when(accessService.requireManagementAccess(1L, 10L)).thenReturn(membership);
        when(connectionService.getRequiredConnection(10L))
                .thenReturn(new DiscordCommunityConnection(community, "100", "GuildUp Discord"));
        when(roleSettingRepository.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(
                new CommunityMemberRoleSetting(community, "200", "클랜원"),
                new CommunityMemberRoleSetting(community, "201", "운영진")
        ));
        when(discordGuildService.getGuildById("100")).thenReturn(guild);
        when(accountRepository.findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of());
        when(memberRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsActiveMembersWhenAnyConfiguredRoleMatchesAndSkipsOthersAndBots() {
        Member memberRole = discordMember("300", "apple", "애플", false, "200");
        Member managerRole = discordMember("301", "manager", "운영진", false, "999", "201");
        Member guest = discordMember("302", "guest", "게스트", false, "999");
        Member bot = discordMember("303", "bot", "봇", true, "200");
        loadMembers(memberRole, managerRole, guest, bot);

        var result = syncService.synchronize(1L, 10L);

        assertThat(result.totalDiscordMembers()).isEqualTo(3);
        assertThat(result.matchedMembers()).isEqualTo(2);
        assertThat(result.createdMembers()).isEqualTo(2);
        var saved = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(accountRepository).saveAll(saved.capture());
        assertThat((List<CommunityMemberAccount>) saved.getValue())
                .extracting(CommunityMemberAccount::getExternalUserId)
                .containsExactly("300", "301");
        assertThat((List<CommunityMemberAccount>) saved.getValue())
                .allMatch(account -> account.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE);
    }

    @Test
    void updatesExistingMemberWithoutInsertingDuplicateAndRefreshesDiscordName() {
        CommunityMember existing = new CommunityMember(community, "이전 별명");
        CommunityMemberAccount account = discordAccount(existing, "300", "old-name", "이전 별명");
        when(accountRepository.findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(account));
        Member discordMember = discordMember("300", "new-name", "새 별명", false, "200");
        loadMembers(discordMember);

        var result = syncService.synchronize(1L, 10L);

        assertThat(result.createdMembers()).isZero();
        assertThat(result.updatedMembers()).isEqualTo(1);
        assertThat(account.getExternalUsername()).isEqualTo("new-name");
        assertThat(account.getExternalDisplayName()).isEqualTo("새 별명");
        assertThat(existing.getNickname()).isEqualTo("새 별명");
        verify(memberRepository, never()).saveAll(anyList());
    }

    @Test
    void marksMissingDiscordMemberLeftWithoutDeleting() {
        CommunityMember existing = new CommunityMember(community, "애플");
        CommunityMemberAccount account = discordAccount(existing, "300", "apple", "애플");
        when(accountRepository.findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(account));
        loadMembers();

        var result = syncService.synchronize(1L, 10L);

        assertThat(result.leftMembers()).isEqualTo(1);
        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.LEFT);
        verify(memberRepository, never()).deleteAll();
        verify(memberRepository, never()).delete(existing);
    }

    @Test
    void reactivatesSameMemberWhenConfiguredRoleReturns() {
        CommunityMember existing = new CommunityMember(community, "애플");
        CommunityMemberAccount account = discordAccount(existing, "300", "apple", "애플");
        existing.markLeft(Instant.parse("2025-02-01T00:00:00Z"));
        when(accountRepository.findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(account));
        Member discordMember = discordMember("300", "apple", "애플", false, "200");
        loadMembers(discordMember);

        var result = syncService.synchronize(1L, 10L);

        assertThat(result.reactivatedMembers()).isEqualTo(1);
        assertThat(result.createdMembers()).isZero();
        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
        verify(memberRepository, never()).saveAll(anyList());
    }

    @Test
    void refusesSyncWithoutRoleSettingsBeforeReadingDiscordMembers() {
        when(roleSettingRepository.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> syncService.synchronize(1L, 10L))
                .isInstanceOf(CommunityMemberRoleSettingRequiredException.class);

        verifyNoInteractions(discordGuildService);
        verify(accountRepository, never()).findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD);
    }

    @Test
    void refusesSyncWithoutDiscordConnection() {
        when(connectionService.getRequiredConnection(10L))
                .thenThrow(new DiscordCommunityConnectionNotFoundException(10L));

        assertThatThrownBy(() -> syncService.synchronize(1L, 10L))
                .isInstanceOf(DiscordCommunityConnectionNotFoundException.class);

        verifyNoInteractions(discordGuildService);
        verifyNoInteractions(roleSettingRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void onlyLoadsMembersForRequestedCommunity() {
        loadMembers();

        syncService.synchronize(1L, 10L);

        verify(accountRepository).findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD);
        verify(accountRepository, never()).findByCommunityIdAndProvider(11L, ExternalAccountProvider.DISCORD);
    }

    private Member discordMember(
            String id,
            String username,
            String displayName,
            boolean bot,
            String... roleIds
    ) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(username);
        when(user.isBot()).thenReturn(bot);
        when(member.getNickname()).thenReturn(displayName);
        when(member.hasTimeJoined()).thenReturn(true);
        when(member.getTimeJoined()).thenReturn(OffsetDateTime.parse("2025-01-01T00:00:00Z"));
        List<Role> roles = java.util.Arrays.stream(roleIds).map(roleId -> {
            Role role = mock(Role.class);
            when(role.getId()).thenReturn(roleId);
            return role;
        }).toList();
        when(member.getRoles()).thenReturn(roles);
        return member;
    }

    @SuppressWarnings("unchecked")
    private void loadMembers(Member... members) {
        Task<List<Member>> task = mock(Task.class);
        when(guild.loadMembers()).thenReturn(task);
        when(task.get()).thenReturn(List.of(members));
    }

    private CommunityMemberAccount discordAccount(
            CommunityMember member,
            String id,
            String username,
            String displayName
    ) {
        return new CommunityMemberAccount(
                member,
                ExternalAccountProvider.DISCORD,
                id,
                username,
                displayName,
                Instant.parse("2025-01-01T00:00:00Z")
        );
    }
}
