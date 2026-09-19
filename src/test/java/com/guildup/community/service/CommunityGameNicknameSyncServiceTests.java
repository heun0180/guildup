package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.GameNicknameRuleStrategyType;
import com.guildup.community.domain.GameType;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.nickname.FullNicknameExtractionStrategy;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.service.PubgPlayerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityGameNicknameSyncServiceTests {

    private final CommunityAccessService access = mock(CommunityAccessService.class);
    private final CommunityGameRepository games = mock(CommunityGameRepository.class);
    private final CommunityGameNicknameRuleRepository rules = mock(CommunityGameNicknameRuleRepository.class);
    private final CommunityMemberRepository members = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accounts = mock(CommunityMemberAccountRepository.class);
    private final PubgPlayerService players = mock(PubgPlayerService.class);
    private final CommunityGameNicknameSyncService service = new CommunityGameNicknameSyncService(
            access,
            games,
            rules,
            members,
            accounts,
            new GameNicknameRuleInferenceService(List.of(new FullNicknameExtractionStrategy())),
            players
    );

    @Test
    void reappliesSavedRuleAndReplacesExistingPubgAccount() {
        Community community = new Community("GuildUp");
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
        identify(community, game);
        CommunityMember newMember = member(1L, community, "new-player");
        CommunityMember storedMember = member(2L, community, "correct-player");
        CommunityMemberAccount storedAccount = new CommunityMemberAccount(
                storedMember, ExternalAccountProvider.PUBG, "wrong-account", "wrong-player"
        );
        CommunityGameNicknameRule rule = new CommunityGameNicknameRule(
                community,
                GameType.BATTLEGROUNDS_KAKAO,
                GameNicknameRuleStrategyType.FULL_NICKNAME,
                null,
                null,
                false,
                null,
                "sample",
                "sample"
        );
        when(games.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(game));
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(rules.findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO))
                .thenReturn(Optional.of(rule));
        when(members.findByCommunityIdAndStatusOrderByIdAsc(10L, CommunityMemberStatus.ACTIVE))
                .thenReturn(List.of(newMember, storedMember));
        when(accounts.findByCommunityIdAndProvider(10L, ExternalAccountProvider.PUBG))
                .thenReturn(List.of(storedAccount));
        when(players.findByNamesFresh("kakao", List.of("new-player", "correct-player")))
                .thenReturn(List.of(
                        new PubgPlayer("account-1", "new-player", List.of()),
                        new PubgPlayer("account-2", "correct-player", List.of())
                ));

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.totalMembers()).isEqualTo(2);
        assertThat(result.synchronizedMembers()).isEqualTo(2);
        assertThat(result.createdAccounts()).isEqualTo(1);
        assertThat(result.updatedAccounts()).isEqualTo(1);
        assertThat(result.failedMembers()).isZero();
        verify(accounts).deleteAllInBatch(org.mockito.ArgumentMatchers.argThat(deleted -> {
            var iterator = deleted.iterator();
            return iterator.hasNext() && iterator.next() == storedAccount && !iterator.hasNext();
        }));
        verify(accounts).saveAll(org.mockito.ArgumentMatchers.argThat(saved -> {
            var synchronizedAccounts = new java.util.ArrayList<CommunityMemberAccount>();
            saved.forEach(synchronizedAccounts::add);
            return synchronizedAccounts.size() == 2
                    && synchronizedAccounts.stream().anyMatch(account ->
                            account.getCommunityMember() == newMember
                                    && account.getExternalUserId().equals("account-1")
                                    && account.getExternalUsername().equals("new-player"))
                    && synchronizedAccounts.stream().anyMatch(account ->
                            account.getCommunityMember() == storedMember
                                    && account.getExternalUserId().equals("account-2")
                                    && account.getExternalUsername().equals("correct-player"));
        }));
        verify(access).requireCommunityAdmin(5L, 10L);
    }

    @Test
    void removesStaleAccountWhenRuleNicknameDoesNotResolve() {
        Community community = new Community("GuildUp");
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
        identify(community, game);
        CommunityMember member = member(1L, community, "not-found-player");
        CommunityMemberAccount staleAccount = new CommunityMemberAccount(
                member, ExternalAccountProvider.PUBG, "stale-account", "stale-player"
        );
        CommunityGameNicknameRule rule = new CommunityGameNicknameRule(
                community,
                GameType.BATTLEGROUNDS_KAKAO,
                GameNicknameRuleStrategyType.FULL_NICKNAME,
                null,
                null,
                false,
                null,
                "sample",
                "sample"
        );
        when(games.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(game));
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(rules.findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO))
                .thenReturn(Optional.of(rule));
        when(members.findByCommunityIdAndStatusOrderByIdAsc(10L, CommunityMemberStatus.ACTIVE))
                .thenReturn(List.of(member));
        when(accounts.findByCommunityIdAndProvider(10L, ExternalAccountProvider.PUBG))
                .thenReturn(List.of(staleAccount));
        when(players.findByNamesFresh("kakao", List.of("not-found-player"))).thenReturn(List.of());

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.synchronizedMembers()).isZero();
        assertThat(result.failedMembers()).isEqualTo(1);
        verify(accounts).deleteAllInBatch(org.mockito.ArgumentMatchers.any());
        verify(accounts, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresSavedRuleBeforeCallingPubg() {
        Community community = new Community("GuildUp");
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_STEAM);
        identify(community, game);
        when(games.findByCommunityIdOrderByIdAsc(10L)).thenReturn(List.of(game));
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(rules.findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_STEAM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.synchronize(5L, 10L, 20L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("인게임 닉네임 규칙");
        verify(players, never()).findByNamesFresh(org.mockito.ArgumentMatchers.anyString(), anyList());
    }

    private CommunityMember member(Long id, Community community, String nickname) {
        CommunityMember member = mock(CommunityMember.class);
        when(member.getId()).thenReturn(id);
        when(member.getCommunity()).thenReturn(community);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getStatus()).thenReturn(CommunityMemberStatus.ACTIVE);
        return member;
    }

    private void identify(Community community, CommunityGame game) {
        ReflectionTestUtils.setField(community, "id", 10L);
        ReflectionTestUtils.setField(game, "id", 20L);
    }
}
