package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMemberRoleSetting;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMemberRealtimeSyncServiceTests {

    private static final Instant AT = Instant.parse("2026-09-15T00:00:00Z");

    private final DiscordCommunityConnectionRepository connections = mock(DiscordCommunityConnectionRepository.class);
    private final CommunityMemberRoleSettingRepository roleSettings = mock(CommunityMemberRoleSettingRepository.class);
    private final CommunityMemberDiscordStateService stateService = mock(CommunityMemberDiscordStateService.class);
    private final CommunityMemberRealtimeSyncService service = new CommunityMemberRealtimeSyncService(
            connections, roleSettings, stateService, new CommunityMemberSyncLockManager(),
            Clock.fixed(AT, ZoneOffset.UTC)
    );

    @Test
    void connectedGuildPassesOneMemberAndConfiguredRolesToSharedStateService() {
        Community community = mock(Community.class);
        when(community.getId()).thenReturn(10L);
        when(connections.findByDiscordGuildId("100")).thenReturn(Optional.of(
                new DiscordCommunityConnection(community, "100", "Guild")
        ));
        when(roleSettings.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(
                new CommunityMemberRoleSetting(community, "200", "클랜원"),
                new CommunityMemberRoleSetting(community, "201", "운영진")
        ));
        Member member = member(false);

        service.synchronize(member);

        verify(stateService).synchronizeMember(10L, member, Set.of("200", "201"), AT);
    }

    @Test
    void unconnectedGuildAndBotAreIgnored() {
        Member human = member(false);
        service.synchronize(human);
        service.synchronize(member(true));

        verify(stateService, never()).synchronizeMember(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void guildLeaveUsesDiscordUserIdAndDoesNotTouchManualMembersDirectly() {
        Community community = mock(Community.class);
        when(community.getId()).thenReturn(10L);
        when(connections.findByDiscordGuildId("100")).thenReturn(Optional.of(
                new DiscordCommunityConnection(community, "100", "Guild")
        ));

        service.memberRemoved("100", "300", false);

        verify(stateService).markMemberLeft(10L, "300", AT);
    }

    private Member member(boolean bot) {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("100");
        User user = mock(User.class);
        when(user.getId()).thenReturn("300");
        when(user.isBot()).thenReturn(bot);
        Member member = mock(Member.class);
        when(member.getGuild()).thenReturn(guild);
        when(member.getUser()).thenReturn(user);
        return member;
    }
}
