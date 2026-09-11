package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import com.guildup.discord.service.DiscordGuildService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityDiscordVoiceActivityServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    private final CommunityAccessService access = mock(CommunityAccessService.class);
    private final DiscordCommunityConnectionService connections = mock(DiscordCommunityConnectionService.class);
    private final CommunityMemberRepository members = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accounts = mock(CommunityMemberAccountRepository.class);
    private final DiscordVoiceSessionRepository sessions = mock(DiscordVoiceSessionRepository.class);
    private final DiscordGuildService guilds = mock(DiscordGuildService.class);
    private final CommunityDiscordVoiceActivityService service = new CommunityDiscordVoiceActivityService(
            access, connections, members, accounts, sessions, guilds,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final Community community = community(1L);

    @Test
    void matchesDiscordAccountToCommunityMemberAndCalculatesFourteenDayTotal() {
        CommunityMember apple = member(10L, "애플", CommunityMemberStatus.ACTIVE, community);
        summarySetup(apple, account(apple, "discord-100"));
        when(sessions.findOverlappingSessions(eq(1L), eq(List.of("discord-100")), any(), eq(NOW)))
                .thenReturn(List.of(
                        closed("discord-100", NOW.minusSeconds(7_200), NOW.minusSeconds(3_600)),
                        closed("discord-100", NOW.minusSeconds(1_800), NOW.minusSeconds(600))
                ));

        var result = service.getSummaries(7L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().communityMemberId()).isEqualTo(10L);
        assertThat(result.getFirst().discordUserId()).isEqualTo("discord-100");
        assertThat(result.getFirst().totalSeconds()).isEqualTo(4_800);
    }

    @Test
    void openSessionDurationUsesCurrentTimeAndIsClippedToFourteenDays() {
        CommunityMember apple = member(10L, "애플", CommunityMemberStatus.ACTIVE, community);
        summarySetup(apple, account(apple, "discord-100"));
        DiscordVoiceSession open = new DiscordVoiceSession(
                community, "guild-1", "discord-100", "voice-1", "배그1",
                NOW.minusSeconds(15L * 24 * 60 * 60)
        );
        when(sessions.findOverlappingSessions(eq(1L), any(), any(), eq(NOW)))
                .thenReturn(List.of(open));

        var result = service.getSummaries(7L, 1L).getFirst();

        assertThat(result.totalSeconds()).isEqualTo(14L * 24 * 60 * 60);
        assertThat(result.currentlyConnected()).isTrue();
    }

    @Test
    void activeMemberWithoutDiscordAccountAppearsWithZeroActivity() {
        CommunityMember apple = member(10L, "애플", CommunityMemberStatus.ACTIVE, community);
        summarySetup(apple);

        var result = service.getSummaries(7L, 1L).getFirst();

        assertThat(result.discordUserId()).isNull();
        assertThat(result.totalSeconds()).isZero();
    }

    @Test
    void detailStillReadsPastSessionsForLeftMemberWithoutDeletingThem() {
        CommunityMember left = member(10L, "애플", CommunityMemberStatus.LEFT, community);
        CommunityMemberAccount account = account(left, "discord-100");
        when(connections.getRequiredConnection(1L)).thenReturn(connection());
        when(members.findById(10L)).thenReturn(Optional.of(left));
        when(accounts.findByCommunityIdAndProvider(1L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(account));
        when(sessions.findOverlappingSessions(eq(1L), eq(List.of("discord-100")), any(), eq(NOW)))
                .thenReturn(List.of(closed("discord-100", NOW.minusSeconds(600), NOW)));
        when(guilds.findGuildById("guild-1")).thenReturn(Optional.empty());

        var result = service.getDetail(7L, 1L, 10L);

        assertThat(result.sessions()).hasSize(1);
        assertThat(result.totalSeconds()).isEqualTo(600);
        verify(sessions, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void repositoryQueryIsAlwaysScopedToCommunityForSameDiscordUserId() {
        CommunityMember apple = member(10L, "애플", CommunityMemberStatus.ACTIVE, community);
        summarySetup(apple, account(apple, "shared-discord-id"));
        when(sessions.findOverlappingSessions(eq(1L), any(), any(), eq(NOW)))
                .thenReturn(List.of(closed("shared-discord-id", NOW.minusSeconds(60), NOW)));

        service.getSummaries(7L, 1L);

        verify(sessions).findOverlappingSessions(eq(1L), eq(List.of("shared-discord-id")), any(), eq(NOW));
    }

    private void summarySetup(CommunityMember member, CommunityMemberAccount... memberAccounts) {
        when(connections.getRequiredConnection(1L)).thenReturn(connection());
        when(members.findByCommunityIdAndStatusOrderByIdAsc(1L, CommunityMemberStatus.ACTIVE))
                .thenReturn(List.of(member));
        when(accounts.findByCommunityIdAndProvider(1L, ExternalAccountProvider.DISCORD))
                .thenReturn(List.of(memberAccounts));
    }

    private DiscordCommunityConnection connection() {
        return new DiscordCommunityConnection(community, "guild-1", "Guild");
    }

    private DiscordVoiceSession closed(String userId, Instant joinedAt, Instant leftAt) {
        DiscordVoiceSession session = new DiscordVoiceSession(
                community, "guild-1", userId, "voice-1", "배그1", joinedAt
        );
        session.close(leftAt);
        return session;
    }

    private CommunityMemberAccount account(CommunityMember member, String discordUserId) {
        CommunityMemberAccount account = mock(CommunityMemberAccount.class);
        when(account.getCommunityMember()).thenReturn(member);
        when(account.getExternalUserId()).thenReturn(discordUserId);
        return account;
    }

    private CommunityMember member(Long id, String nickname, CommunityMemberStatus status, Community community) {
        CommunityMember member = mock(CommunityMember.class);
        when(member.getId()).thenReturn(id);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getStatus()).thenReturn(status);
        when(member.getCommunity()).thenReturn(community);
        return member;
    }

    private Community community(Long id) {
        Community value = mock(Community.class);
        when(value.getId()).thenReturn(id);
        return value;
    }
}
