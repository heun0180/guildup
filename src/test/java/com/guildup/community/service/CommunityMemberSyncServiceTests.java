package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMemberRoleSetting;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommunityMemberSyncServiceTests {

    private static final Instant SYNCED_AT = Instant.parse("2026-09-15T00:00:00Z");

    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final DiscordCommunityConnectionService connectionService = mock(DiscordCommunityConnectionService.class);
    private final CommunityMemberRoleSettingRepository roleSettings = mock(CommunityMemberRoleSettingRepository.class);
    private final DiscordGuildService guildService = mock(DiscordGuildService.class);
    private final DiscordMemberService memberService = mock(DiscordMemberService.class);
    private final CommunityMemberDiscordStateService stateService = mock(CommunityMemberDiscordStateService.class);
    private final CommunityMemberSyncService service = new CommunityMemberSyncService(
            accessService, connectionService, roleSettings, guildService, memberService, stateService,
            new CommunityMemberSyncLockManager(), Clock.fixed(SYNCED_AT, ZoneOffset.UTC)
    );
    private final Community community = new Community("GuildUp");
    private final Guild guild = mock(Guild.class);

    @BeforeEach
    void setUp() {
        CommunityUser membership = mock(CommunityUser.class);
        when(membership.getCommunity()).thenReturn(community);
        when(accessService.requireManagementAccess(1L, 10L)).thenReturn(membership);
        when(connectionService.getRequiredConnection(10L))
                .thenReturn(new DiscordCommunityConnection(community, "100", "Guild"));
        when(roleSettings.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(
                new CommunityMemberRoleSetting(community, "200", "클랜원")
        ));
        when(guildService.getGuildById("100")).thenReturn(guild);
    }

    @Test
    void manualSyncChecksAccessLoadsFreshSnapshotAndUsesSharedStateService() {
        Member member = mock(Member.class);
        List<Member> members = List.of(member);
        CommunityMemberSyncResponse expected = new CommunityMemberSyncResponse(
                1, 1, 1, 0, 0, 0, SYNCED_AT
        );
        when(memberService.loadMembersForReconciliation(guild)).thenReturn(members);
        when(stateService.reconcileSnapshot(10L, members, Set.of("200"), SYNCED_AT)).thenReturn(expected);

        assertThat(service.synchronize(1L, 10L)).isSameAs(expected);

        verify(accessService).requireManagementAccess(1L, 10L);
        verify(memberService).loadMembersForReconciliation(guild);
    }

    @Test
    void emptyRoleSettingsMarkDiscordAccountsLeftWithoutLoadingDiscord() {
        when(roleSettings.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of());
        CommunityMemberSyncResponse expected = new CommunityMemberSyncResponse(
                0, 0, 0, 0, 0, 2, SYNCED_AT
        );
        when(stateService.reconcileSnapshot(10L, List.of(), Set.of(), SYNCED_AT)).thenReturn(expected);

        assertThat(service.synchronize(1L, 10L)).isSameAs(expected);

        verifyNoInteractions(guildService, memberService);
    }

    @Test
    void internalRecoveryUsesSameReconciliationWithoutUserAccess() {
        when(memberService.loadMembersForReconciliation(guild)).thenReturn(List.of());
        service.reconcile(10L);

        verifyNoInteractions(accessService);
        verify(stateService).reconcileSnapshot(10L, List.of(), Set.of("200"), SYNCED_AT);
    }

    @Test
    void missingConnectionFailsBeforeDiscordMemberLoad() {
        when(connectionService.getRequiredConnection(10L))
                .thenThrow(new DiscordCommunityConnectionNotFoundException(10L));

        assertThatThrownBy(() -> service.synchronize(1L, 10L))
                .isInstanceOf(DiscordCommunityConnectionNotFoundException.class);

        verify(memberService, never()).loadMembersForReconciliation(guild);
    }
}
