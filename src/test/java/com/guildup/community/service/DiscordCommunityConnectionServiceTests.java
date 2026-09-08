package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.exception.DiscordCommunityConnectionConflictException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.dao.DataIntegrityViolationException;

class DiscordCommunityConnectionServiceTests {

    private final CommunityRepository communityRepository = mock(CommunityRepository.class);
    private final DiscordCommunityConnectionRepository connectionRepository =
            mock(DiscordCommunityConnectionRepository.class);
    private final DiscordCommunityConnectionService connectionService =
            new DiscordCommunityConnectionService(communityRepository, connectionRepository);

    @Test
    void findsDiscordConnectionByCommunityId() {
        Community community = new Community("GuildUp 클랜");
        DiscordCommunityConnection connection =
                new DiscordCommunityConnection(community, "100", "GuildUp Discord");
        when(communityRepository.existsById(1L)).thenReturn(true);
        when(connectionRepository.findByCommunityId(1L)).thenReturn(Optional.of(connection));

        DiscordCommunityConnection result = connectionService.getRequiredConnection(1L);

        assertThat(result).isSameAs(connection);
        verify(connectionRepository).findByCommunityId(1L);
    }

    @Test
    void throwsWhenCommunityHasNoDiscordConnection() {
        when(communityRepository.existsById(1L)).thenReturn(true);
        when(connectionRepository.findByCommunityId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connectionService.getRequiredConnection(1L))
                .isInstanceOf(DiscordCommunityConnectionNotFoundException.class);
    }

    @Test
    void throwsWhenCommunityDoesNotExist() {
        when(communityRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> connectionService.getRequiredConnection(99L))
                .isInstanceOf(CommunityNotFoundException.class);
    }

    @Test
    void doesNotReplaceDifferentGuildWithoutExplicitPolicy() {
        Community community = mock(Community.class);
        DiscordCommunityConnection existingConnection = mock(DiscordCommunityConnection.class);
        when(community.getId()).thenReturn(1L);
        when(existingConnection.getDiscordGuildId()).thenReturn("100");
        when(communityRepository.findById(1L)).thenReturn(Optional.of(community));
        when(connectionRepository.findByDiscordGuildId("200")).thenReturn(Optional.empty());
        when(connectionRepository.findByCommunityId(1L)).thenReturn(Optional.of(existingConnection));

        assertThatThrownBy(() -> connectionService.connect(1L, "200", "다른 서버"))
                .isInstanceOf(DiscordCommunityConnectionConflictException.class);
        verify(connectionRepository, never()).save(existingConnection);
    }
    @Test
    void rejectsGuildAlreadyConnectedToAnotherCommunity() {
        Community original = mock(Community.class);
        Community selected = mock(Community.class);
        when(original.getId()).thenReturn(1L);
        when(communityRepository.findById(2L)).thenReturn(Optional.of(selected));
        when(connectionRepository.findByDiscordGuildId("1401450300136751234"))
                .thenReturn(Optional.of(new DiscordCommunityConnection(original,
                        "1401450300136751234", "Cheeeze")));

        assertThatThrownBy(() -> connectionService.connect(2L, "1401450300136751234", "Cheeeze"))
                .isInstanceOf(com.guildup.community.exception.DiscordGuildAlreadyConnectedException.class);
        verify(connectionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatesDatabaseUniqueRaceToGuildAlreadyConnectedBusinessException() {
        Community selected = mock(Community.class);
        when(communityRepository.findById(2L)).thenReturn(Optional.of(selected));
        when(connectionRepository.findByDiscordGuildId("100")).thenReturn(Optional.empty());
        when(connectionRepository.findByCommunityId(2L)).thenReturn(Optional.empty());
        when(connectionRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("uk_discord_connection_guild"));

        assertThatThrownBy(() -> connectionService.connect(2L, "100", "Cheeeze"))
                .isInstanceOf(com.guildup.community.exception.DiscordGuildAlreadyConnectedException.class)
                .extracting("discordGuildId")
                .isEqualTo("100");
    }

}
