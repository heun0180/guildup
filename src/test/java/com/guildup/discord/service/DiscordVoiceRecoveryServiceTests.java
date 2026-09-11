package com.guildup.discord.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordVoiceRecoveryServiceTests {

    private static final Instant RESTARTED_AT = Instant.parse("2026-09-11T12:00:00Z");

    private final DiscordCommunityConnectionRepository connections =
            mock(DiscordCommunityConnectionRepository.class);
    private final DiscordVoiceSessionRepository sessions = mock(DiscordVoiceSessionRepository.class);
    private final DiscordVoiceSessionService sessionService = mock(DiscordVoiceSessionService.class);
    private final DiscordVoiceRecoveryService recovery = new DiscordVoiceRecoveryService(
            connections, sessions, sessionService,
            Clock.fixed(RESTARTED_AT, ZoneOffset.UTC)
    );
    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);
    private final Community community = community();

    @Test
    void closesDatabaseSessionAtRestartWhenUserIsNoLongerConnected() {
        connectedGuild();
        DiscordVoiceSession open = new DiscordVoiceSession(
                community, "guild-1", "user-1", "voice-old", "배그1", RESTARTED_AT.minusSeconds(3_600)
        );
        when(guild.getVoiceStates()).thenReturn(List.of());
        when(sessions.findByCommunityIdAndLeftAtIsNull(1L)).thenReturn(List.of(open));

        recovery.reconcile(jda);

        verify(sessionService).handleVoiceUpdate(
                "guild-1", "user-1", null, null, RESTARTED_AT
        );
    }

    @Test
    void opensCurrentVoiceStateFromRestartTime() {
        connectedGuild();
        GuildVoiceState state = mock(GuildVoiceState.class);
        Member member = mock(Member.class);
        User user = mock(User.class);
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(state.inAudioChannel()).thenReturn(true);
        when(state.getMember()).thenReturn(member);
        when(member.getUser()).thenReturn(user);
        when(user.isBot()).thenReturn(false);
        when(member.getId()).thenReturn("user-1");
        when(state.getChannel()).thenReturn(channel);
        when(channel.getId()).thenReturn("voice-1");
        when(channel.getName()).thenReturn("배그1");
        when(guild.getVoiceStates()).thenReturn(List.of(state));
        when(sessions.findByCommunityIdAndLeftAtIsNull(1L)).thenReturn(List.of());

        recovery.reconcile(jda);

        verify(sessionService).handleVoiceUpdate(
                "guild-1", "user-1", "voice-1", "배그1", RESTARTED_AT
        );
    }

    private void connectedGuild() {
        when(connections.findAllWithCommunity()).thenReturn(List.of(
                new DiscordCommunityConnection(community, "guild-1", "Guild")
        ));
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.getId()).thenReturn("guild-1");
    }

    private Community community() {
        Community value = mock(Community.class);
        when(value.getId()).thenReturn(1L);
        return value;
    }
}
