package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.domain.GameType;
import com.guildup.community.repository.CommunityGameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CommunityGameAccessServiceTests {
    private final CommunityAccessService communities = mock(CommunityAccessService.class);
    private final CommunityGameRepository games = mock(CommunityGameRepository.class);
    private final CommunityGameAccessService service = new CommunityGameAccessService(communities, games);

    @Test
    void acceptsOwnedGameWithCapability() {
        CommunityGame game = game(1L, 10L);
        when(games.findById(10L)).thenReturn(Optional.of(game));

        assertThat(service.requireAccessible(7L, 1L, 10L, GameCapability.BINGO)).isSameAs(game);
        verify(communities).requireCommunityMember(7L, 1L);
    }

    @Test
    void rejectsAnotherCommunityAndMissingGame() {
        when(games.findById(10L)).thenReturn(Optional.of(game(2L, 10L)));
        assertStatus(404, () -> service.requireAccessible(7L, 1L, 10L, GameCapability.ACTIVITY));
        when(games.findById(99L)).thenReturn(Optional.empty());
        assertStatus(404, () -> service.requireAccessible(7L, 1L, 99L, GameCapability.ACTIVITY));
    }

    @Test
    void rejectsUnsupportedCapability() {
        CommunityGame game = mock(CommunityGame.class);
        Community community = community(1L);
        when(game.getCommunity()).thenReturn(community);
        when(game.supports(GameCapability.BINGO)).thenReturn(false);
        when(games.findById(10L)).thenReturn(Optional.of(game));
        assertStatus(400, () -> service.requireAccessible(7L, 1L, 10L, GameCapability.BINGO));
    }

    private CommunityGame game(Long communityId, Long gameId) {
        CommunityGame game = new CommunityGame(community(communityId), GameType.BATTLEGROUNDS_KAKAO);
        ReflectionTestUtils.setField(game, "id", gameId);
        return game;
    }

    private Community community(Long id) {
        Community community = new Community("community-" + id);
        ReflectionTestUtils.setField(community, "id", id);
        return community;
    }

    private void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
}
