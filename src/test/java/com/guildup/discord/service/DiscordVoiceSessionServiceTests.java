package com.guildup.discord.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordVoiceSessionServiceTests {

    private final DiscordCommunityConnectionRepository connections =
            mock(DiscordCommunityConnectionRepository.class);
    private final DiscordVoiceSessionRepository sessions = mock(DiscordVoiceSessionRepository.class);
    private final DiscordVoiceSessionService service = new DiscordVoiceSessionService(connections, sessions);
    private final Community community = community(1L);
    private final Instant at = Instant.parse("2026-09-11T10:00:00Z");

    @Test
    void joiningVoiceChannelCreatesOpenSession() {
        connected();

        service.handleVoiceUpdate("guild-1", "user-1", "channel-1", "배그1", at);

        ArgumentCaptor<DiscordVoiceSession> captor = ArgumentCaptor.forClass(DiscordVoiceSession.class);
        verify(sessions).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDiscordUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getDiscordChannelId()).isEqualTo("channel-1");
        assertThat(captor.getValue().getJoinedAt()).isEqualTo(at);
        assertThat(captor.getValue().isOpen()).isTrue();
    }

    @Test
    void leavingVoiceChannelClosesOpenSession() {
        connected();
        DiscordVoiceSession open = openSession("channel-1", at.minusSeconds(600));
        when(sessions.findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(1L, "user-1"))
                .thenReturn(Optional.of(open));

        service.handleVoiceUpdate("guild-1", "user-1", null, null, at);

        assertThat(open.getLeftAt()).isEqualTo(at);
        verify(sessions).save(open);
        verify(sessions, never()).saveAndFlush(any(DiscordVoiceSession.class));
    }

    @Test
    void movingChannelClosesOldSessionAndCreatesNewSessionAtSameTime() {
        connected();
        DiscordVoiceSession open = openSession("channel-1", at.minusSeconds(600));
        when(sessions.findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(1L, "user-1"))
                .thenReturn(Optional.of(open));

        service.handleVoiceUpdate("guild-1", "user-1", "channel-2", "배그2", at);

        assertThat(open.getLeftAt()).isEqualTo(at);
        ArgumentCaptor<DiscordVoiceSession> captor = ArgumentCaptor.forClass(DiscordVoiceSession.class);
        verify(sessions).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDiscordChannelId()).isEqualTo("channel-2");
        assertThat(captor.getValue().getJoinedAt()).isEqualTo(at);
    }

    @Test
    void duplicateJoinForSameChannelKeepsSingleOpenSession() {
        connected();
        when(sessions.findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(1L, "user-1"))
                .thenReturn(Optional.of(openSession("channel-1", at.minusSeconds(60))));

        service.handleVoiceUpdate("guild-1", "user-1", "channel-1", "배그1", at);

        verify(sessions, never()).save(any(DiscordVoiceSession.class));
        verify(sessions, never()).saveAndFlush(any(DiscordVoiceSession.class));
    }

    @Test
    void eventFromUnconnectedGuildIsIgnored() {
        when(connections.findByDiscordGuildId("unknown-guild")).thenReturn(Optional.empty());

        service.handleVoiceUpdate("unknown-guild", "user-1", "channel-1", "배그1", at);

        verify(sessions, never()).findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(any(), any());
        verify(sessions, never()).save(any());
    }

    private void connected() {
        when(connections.findByDiscordGuildId("guild-1")).thenReturn(Optional.of(
                new DiscordCommunityConnection(community, "guild-1", "Guild")
        ));
    }

    private DiscordVoiceSession openSession(String channelId, Instant joinedAt) {
        return new DiscordVoiceSession(community, "guild-1", "user-1", channelId, "채널", joinedAt);
    }

    private Community community(Long id) {
        Community value = mock(Community.class);
        when(value.getId()).thenReturn(id);
        return value;
    }
}
