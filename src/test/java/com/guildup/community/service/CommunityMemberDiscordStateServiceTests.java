package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMemberDiscordStateServiceTests {

    private static final Instant AT = Instant.parse("2026-09-15T00:00:00Z");

    private final DiscordCommunityConnectionRepository connections = mock(DiscordCommunityConnectionRepository.class);
    private final CommunityMemberRepository members = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accounts = mock(CommunityMemberAccountRepository.class);
    private final CommunityMemberDiscordStateService service = new CommunityMemberDiscordStateService(
            connections, members, accounts, new DiscordMemberService()
    );
    private final Community community = new Community("GuildUp");

    @BeforeEach
    void setUp() {
        when(connections.findByCommunityId(10L)).thenReturn(Optional.of(
                new DiscordCommunityConnection(community, "100", "Guild")
        ));
        when(accounts.findByCommunityIdAndProviderAndExternalUserId(
                10L, ExternalAccountProvider.DISCORD, "300"
        )).thenReturn(Optional.empty());
        when(members.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredRoleCreatesMemberAndDiscordAccount() {
        Member discordMember = discordMember(false, "200");

        var outcome = service.synchronizeMember(10L, discordMember, Set.of("200"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.CREATED);
        ArgumentCaptor<List<CommunityMemberAccount>> captor = ArgumentCaptor.forClass(List.class);
        verify(accounts).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(account -> {
            assertThat(account.getExternalUserId()).isEqualTo("300");
            assertThat(account.getExternalUsername()).isEqualTo("apple");
            assertThat(account.getExternalDisplayName()).isEqualTo("애플");
            assertThat(account.getCommunityMember().getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
        });
    }

    @Test
    void leftMemberIsReactivatedWithoutCreatingDuplicate() {
        CommunityMember existing = new CommunityMember(community, "이전 이름");
        existing.markLeft(AT.minusSeconds(60));
        existingAccount(existing);

        var outcome = service.synchronizeMember(10L, discordMember(false, "200"), Set.of("200"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.REACTIVATED);
        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
        assertThat(existing.getNickname()).isEqualTo("애플");
        verify(members, never()).saveAll(anyList());
        verify(accounts, never()).saveAll(anyList());
    }

    @Test
    void activeMemberUpdateDoesNotCreateDuplicate() {
        CommunityMember existing = new CommunityMember(community, "애플");
        existingAccount(existing);

        var outcome = service.synchronizeMember(10L, discordMember(false, "200"), Set.of("200"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.UPDATED);
        verify(members, never()).saveAll(anyList());
        verify(accounts, never()).saveAll(anyList());
    }

    @Test
    void removingOneOfTwoConfiguredRolesKeepsMemberActive() {
        CommunityMember existing = new CommunityMember(community, "애플");
        existingAccount(existing);

        service.synchronizeMember(10L, discordMember(false, "201"), Set.of("200", "201"), AT);

        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    @Test
    void removingLastConfiguredRoleMarksMemberLeft() {
        CommunityMember existing = new CommunityMember(community, "애플");
        existingAccount(existing);

        var outcome = service.synchronizeMember(10L, discordMember(false, "999"), Set.of("200", "201"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.LEFT);
        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.LEFT);
    }

    @Test
    void unrelatedRoleForUnknownUserIsIgnored() {
        var outcome = service.synchronizeMember(10L, discordMember(false, "999"), Set.of("200"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.IGNORED);
        verify(members, never()).saveAll(anyList());
        verify(accounts, never()).saveAll(anyList());
    }

    @Test
    void botIsIgnoredBeforeDatabaseLookup() {
        var outcome = service.synchronizeMember(10L, discordMember(true, "200"), Set.of("200"), AT);

        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.IGNORED);
        verify(connections, never()).findByCommunityId(10L);
    }

    @Test
    void guildLeaveMarksOnlyDiscordLinkedMemberLeftAndIsIdempotent() {
        CommunityMember existing = new CommunityMember(community, "애플");
        existingAccount(existing);

        assertThat(service.markMemberLeft(10L, "300", AT))
                .isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.LEFT);
        assertThat(service.markMemberLeft(10L, "300", AT.plusSeconds(1)))
                .isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.IGNORED);
        assertThat(existing.getStatus()).isEqualTo(CommunityMemberStatus.LEFT);
    }

    @Test
    void emptyRoleReconciliationLeavesDiscordMembersButNotManualMembers() {
        CommunityMember discord = new CommunityMember(community, "Discord");
        CommunityMember manual = new CommunityMember(community, "Manual");
        CommunityMemberAccount account = account(discord);
        when(accounts.findByCommunityIdAndProvider(10L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(account));

        var result = service.reconcileSnapshot(10L, List.of(), Set.of(), AT);

        assertThat(result.leftMembers()).isEqualTo(1);
        assertThat(discord.getStatus()).isEqualTo(CommunityMemberStatus.LEFT);
        assertThat(manual.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
        assertThat(connections.findByCommunityId(10L).orElseThrow().getLastMemberSyncedAt()).isEqualTo(AT);
    }

    private void existingAccount(CommunityMember member) {
        when(accounts.findByCommunityIdAndProviderAndExternalUserId(
                10L, ExternalAccountProvider.DISCORD, "300"
        )).thenReturn(Optional.of(account(member)));
    }

    private CommunityMemberAccount account(CommunityMember member) {
        return new CommunityMemberAccount(
                member, ExternalAccountProvider.DISCORD, "300", "apple", "애플", AT.minusSeconds(3_600)
        );
    }

    private Member discordMember(boolean bot, String... roleIds) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("300");
        when(user.getName()).thenReturn("apple");
        when(user.isBot()).thenReturn(bot);
        when(member.getNickname()).thenReturn("애플");
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
}
