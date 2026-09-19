package com.guildup.community.service;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.GameType;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityGameActivityRuleServiceTests {

    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final CommunityGameRepository communityGameRepository = mock(CommunityGameRepository.class);
    private final CommunityGameActivityRuleRepository ruleRepository = mock(CommunityGameActivityRuleRepository.class);
    private final CommunityGameActivityRuleService ruleService = new CommunityGameActivityRuleService(
            accessService, communityGameRepository, ruleRepository
    );
    private final Community community = new Community("GuildUp");
    private final CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
    private final AtomicReference<CommunityGameActivityRule> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(community, "id", 10L);
        ReflectionTestUtils.setField(game, "id", 20L);
        when(communityGameRepository.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(game));
        when(communityGameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(ruleRepository.findByCommunityGameId(20L))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(ruleRepository.save(any(CommunityGameActivityRule.class))).thenAnswer(invocation -> {
            CommunityGameActivityRule rule = invocation.getArgument(0);
            stored.set(rule);
            return rule;
        });
    }

    @Test
    void readsTheDefaultCheeseClanRule() {
        stored.set(CommunityGameActivityRule.defaultRule(game));

        var response = ruleService.getRule(1L, 10L, 20L);

        assertThat(response.gameType()).isEqualTo("BATTLEGROUNDS_KAKAO");
        assertThat(response.activityPeriodDays()).isEqualTo(14);
        assertThat(response.minimumClanMembersInRoster()).isEqualTo(2);
    }

    @Test
    void updatesTheExistingRuleWithoutCreatingADuplicate() {
        stored.set(CommunityGameActivityRule.defaultRule(game));
        CommunityGameActivityRule original = stored.get();

        var membersUpdated = ruleService.updateRule(1L, 10L, 20L, 14, 3);
        var periodUpdated = ruleService.updateRule(1L, 10L, 20L, 30, 3);

        assertThat(stored.get()).isSameAs(original);
        assertThat(membersUpdated.minimumClanMembersInRoster()).isEqualTo(3);
        assertThat(periodUpdated.activityPeriodDays()).isEqualTo(30);
    }

    @Test
    void rejectsMissingAndOutOfRangeValues() {
        assertBadRequest(null, 2);
        assertBadRequest(14, null);
        assertBadRequest(0, 2);
        assertBadRequest(366, 2);
        assertBadRequest(14, 1);
        assertBadRequest(14, 5);
    }

    @Test
    void rejectsACommunityWithoutASupportedBattlegroundsGame() {
        when(communityGameRepository.findById(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ruleService.getRule(1L, 10L, 20L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checksManagementAccessBeforeChangingAnything() {
        when(accessService.requireCommunityAdmin(1L, 10L))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> ruleService.updateRule(1L, 10L, 20L, 30, 3))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(ruleRepository, never()).save(any());
    }

    private void assertBadRequest(Integer period, Integer minimumMembers) {
        assertThatThrownBy(() -> ruleService.updateRule(1L, 10L, 20L, period, minimumMembers))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
