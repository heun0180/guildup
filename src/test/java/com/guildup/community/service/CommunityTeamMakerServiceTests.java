package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.GameType;
import com.guildup.community.dto.TeamGenerationRequest;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgSeasonStats;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.pubg.service.PubgSeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityTeamMakerServiceTests {
    private final CommunityAccessService access = mock(CommunityAccessService.class);
    private final CommunityMemberRepository members = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accounts = mock(CommunityMemberAccountRepository.class);
    private final CommunityGameRepository games = mock(CommunityGameRepository.class);
    private final CommunityGameNicknameRuleRepository rules = mock(CommunityGameNicknameRuleRepository.class);
    private final GameNicknameRuleInferenceService nicknameInference = mock(GameNicknameRuleInferenceService.class);
    private final PubgPlayerService players = mock(PubgPlayerService.class);
    private final PubgSeasonService seasons = mock(PubgSeasonService.class);
    private final CommunityTeamMakerService service = new CommunityTeamMakerService(
            access, members, accounts, games, rules, nicknameInference, players, seasons,
            new TeamBalanceService()
    );
    private CommunityMember member;

    @BeforeEach
    void setUp() {
        Community community = new Community("클랜");
        ReflectionTestUtils.setField(community, "id", 10L);
        member = new CommunityMember(community, "절미");
        ReflectionTestUtils.setField(member, "id", 20L);
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
        CommunityMemberAccount account = new CommunityMemberAccount(
                member, ExternalAccountProvider.PUBG, "account.20", "jul-mi"
        );
        when(games.findFirstByCommunityIdOrderByIdAsc(10L)).thenReturn(Optional.of(game));
        when(members.findByCommunityIdAndStatusOrderByIdAsc(10L, com.guildup.community.domain.CommunityMemberStatus.ACTIVE))
                .thenReturn(List.of(member));
        when(accounts.findByCommunityIdAndProvider(10L, ExternalAccountProvider.PUBG))
                .thenReturn(List.of(account));
        when(rules.findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO))
                .thenReturn(Optional.empty());
        when(seasons.getCurrentAndPrevious("kakao"))
                .thenReturn(new PubgSeasonService.SeasonPair("current", "previous"));
    }

    @Test
    void calculatesAverageFromDamageAndRounds() {
        when(seasons.getCombinedStats("kakao", List.of("account.20"), List.of("current", "previous")))
                .thenReturn(Map.of("account.20", new PubgSeasonStats("account.20", 1_600, 6)));

        var response = service.generate(1L, 10L,
                new TeamGenerationRequest(List.of(20L), true, true, 4, 1L));

        assertThat(response.missingStatsParticipants()).isEmpty();
        assertThat(response.participants().getFirst().averageDamage()).isEqualTo(266.7);
        assertThat(response.teams()).hasSize(1);
    }

    @Test
    void returnsMissingParticipantWithoutCreatingTeamsWhenRoundsAreZero() {
        when(seasons.getCombinedStats("kakao", List.of("account.20"), List.of("current")))
                .thenReturn(Map.of("account.20", PubgSeasonStats.empty("account.20")));

        var response = service.generate(1L, 10L,
                new TeamGenerationRequest(List.of(20L), true, false, 4, 1L));

        assertThat(response.teams()).isEmpty();
        assertThat(response.missingStatsParticipants()).singleElement()
                .satisfies(item -> assertThat(item.discordNickname()).isEqualTo("절미"));
    }

    @Test
    void propagatesPubgApiFailure() {
        when(seasons.getCurrentAndPrevious("kakao")).thenThrow(new PubgApiException("PUBG 장애"));

        assertThatThrownBy(() -> service.generate(1L, 10L,
                new TeamGenerationRequest(List.of(20L), true, false, 4, 1L)))
                .isInstanceOf(PubgApiException.class)
                .hasMessageContaining("PUBG 장애");
    }

    @Test
    void acceptsManuallyEnteredAverageDamageForMissingSeasonStats() {
        var response = service.rebalance(1L, 10L, new com.guildup.community.dto.TeamRebalanceRequest(
                List.of(new com.guildup.community.dto.TeamMakerParticipantResponse(
                        20L, "절미", "jul-mi", 325.5, 1L, 325.5
                )),
                4,
                1L
        ));

        assertThat(response.missingStatsParticipants()).isEmpty();
        assertThat(response.participants()).singleElement()
                .satisfies(participant -> assertThat(participant.averageDamage()).isEqualTo(325.5));
        assertThat(response.teams()).singleElement()
                .satisfies(team -> assertThat(team.averageDamage()).isEqualTo(325.5));
    }

    @Test
    void deniesNonManagerAccess() {
        when(access.requireManagementAccess(1L, 10L)).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "denied")
        );

        assertThatThrownBy(() -> service.getParticipants(1L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
