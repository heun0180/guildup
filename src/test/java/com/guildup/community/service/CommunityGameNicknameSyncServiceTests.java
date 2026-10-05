package com.guildup.community.service;

import com.guildup.pubg.model.PubgPlatform;

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
                storedMember, PubgPlatform.KAKAO, "wrong-account", "wrong-player"
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
        when(accounts.findByCommunityIdAndProviderAndPlatform(10L, ExternalAccountProvider.PUBG,PubgPlatform.KAKAO))
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
    void preservesExistingAccountWhenRuleNicknameDoesNotResolve() {
        Community community = new Community("GuildUp");
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
        identify(community, game);
        CommunityMember member = member(1L, community, "not-found-player");
        CommunityMemberAccount staleAccount = new CommunityMemberAccount(
                member, PubgPlatform.KAKAO, "stale-account", "stale-player"
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
        when(accounts.findByCommunityIdAndProviderAndPlatform(10L, ExternalAccountProvider.PUBG,PubgPlatform.KAKAO))
                .thenReturn(List.of(staleAccount));
        when(players.findByNamesFresh("kakao", List.of("not-found-player"))).thenReturn(List.of());

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.synchronizedMembers()).isZero();
        assertThat(result.failedMembers()).isEqualTo(1);
        verify(accounts, never()).deleteAllInBatch(org.mockito.ArgumentMatchers.any());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PubgPlatform.class)
    void unresolvedLegacyAccountStopsSyncBeforePubgLookupOrAccountReplacement(PubgPlatform platform) {
        Community community = new Community("GuildUp");
        GameType type = platform == PubgPlatform.KAKAO
                ? GameType.BATTLEGROUNDS_KAKAO : GameType.BATTLEGROUNDS_STEAM;
        CommunityGame game = new CommunityGame(community, type);
        identify(community, game);
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(accounts.existsByCommunityIdAndProviderAndPlatformIsNull(10L, ExternalAccountProvider.PUBG))
                .thenReturn(true);

        assertThatThrownBy(() -> service.synchronize(5L, 10L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("플랫폼 migration");
                });
        verify(players, never()).findByNamesFresh(org.mockito.ArgumentMatchers.anyString(), anyList());
        verify(accounts, never()).deleteAllInBatch(org.mockito.ArgumentMatchers.any());
        verify(accounts, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PubgPlatform.class)
    void synchronizingOnePlatformNeverDeletesOrUpdatesTheOther(PubgPlatform platform) {
        Community community = new Community("GuildUp");
        GameType type = platform == PubgPlatform.KAKAO ? GameType.BATTLEGROUNDS_KAKAO : GameType.BATTLEGROUNDS_STEAM;
        CommunityGame game = new CommunityGame(community, type);
        identify(community, game);
        CommunityMember member = member(1L, community, "new-name");
        var otherPlatform = platform == PubgPlatform.KAKAO
                ? PubgPlatform.STEAM : PubgPlatform.KAKAO;
        CommunityMemberAccount selected = new CommunityMemberAccount(member, platform, "old-selected", "old-name");
        CommunityMemberAccount other = new CommunityMemberAccount(member, otherPlatform, "other-id", "other-name");
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(rules.findByCommunityIdAndGameType(10L, type)).thenReturn(Optional.of(new CommunityGameNicknameRule(
                community, type, GameNicknameRuleStrategyType.FULL_NICKNAME, null, null, false, null, "sample", "sample")));
        when(members.findByCommunityIdAndStatusOrderByIdAsc(10L, CommunityMemberStatus.ACTIVE)).thenReturn(List.of(member));
        when(accounts.findByCommunityIdAndProviderAndPlatform(10L, ExternalAccountProvider.PUBG, platform)).thenReturn(List.of(selected));
        when(players.findByNamesFresh(platform.getShard(), List.of("new-name")))
                .thenReturn(List.of(new PubgPlayer("new-id", "new-name", List.of())));

        service.synchronize(5L, 10L, 20L);

        verify(accounts).deleteAllInBatch(org.mockito.ArgumentMatchers.argThat(deleted -> {
            var values = new java.util.ArrayList<CommunityMemberAccount>(); deleted.forEach(values::add);
            return values.equals(List.of(selected));
        }));
        verify(accounts).saveAll(org.mockito.ArgumentMatchers.argThat(saved -> {
            var values = new java.util.ArrayList<CommunityMemberAccount>(); saved.forEach(values::add);
            return values.size() == 1 && values.getFirst().getPlatform() == platform && values.getFirst().getExternalUserId().equals("new-id");
        }));
        verify(accounts, never()).findByCommunityIdAndProviderAndPlatform(10L, ExternalAccountProvider.PUBG, otherPlatform);
        assertThat(other.getExternalUserId()).isEqualTo("other-id");
        assertThat(other.getExternalUsername()).isEqualTo("other-name");
    }

    @Test
    void partialLookupFailureOnlyReplacesSuccessfullyVerifiedMembers() {
        Community community = new Community("GuildUp");
        CommunityMember success = member(1L, community, "success");
        CommunityMember missing = member(2L, community, "missing");
        CommunityMemberAccount oldSuccess = account(success, "old-success");
        CommunityMemberAccount oldMissing = account(missing, "old-missing");
        prepare(community, List.of(success, missing), List.of(oldSuccess, oldMissing),
                List.of(new PubgPlayer("new-success", "success", List.of())));

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.synchronizedMembers()).isEqualTo(1);
        assertThat(result.updatedAccounts()).isEqualTo(1);
        assertThat(result.failedMembers()).isEqualTo(1);
        verifyReplacement(List.of(oldSuccess), "new-success");
        assertThat(oldMissing.getExternalUserId()).isEqualTo("old-missing");
    }

    @Test
    void nicknameExtractionFailurePreservesExistingConnection() {
        Community community = new Community("GuildUp");
        CommunityMember blank = member(1L, community, " ");
        CommunityMemberAccount existing = account(blank, "existing");
        prepare(community, List.of(blank), List.of(existing), List.of());

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.failedMembers()).isEqualTo(1);
        assertNoAccountWrites();
        assertThat(existing.getExternalUserId()).isEqualTo("existing");
    }

    @Test
    void duplicateResolvedAccountPreservesBothExistingConnections() {
        Community community = new Community("GuildUp");
        CommunityMember first = member(1L, community, "first");
        CommunityMember second = member(2L, community, "second");
        prepare(community, List.of(first, second), List.of(account(first, "old-first"), account(second, "old-second")),
                List.of(new PubgPlayer("duplicate", "first", List.of()),
                        new PubgPlayer("duplicate", "second", List.of())));

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.failedMembers()).isEqualTo(2);
        assertThat(result.synchronizedMembers()).isZero();
        assertNoAccountWrites();
    }

    @Test
    void cannotClaimAccountRetainedByMemberWhoseLookupFailed() {
        Community community = new Community("GuildUp");
        CommunityMember first = member(1L, community, "first");
        CommunityMember missing = member(2L, community, "missing");
        prepare(community, List.of(first, missing), List.of(account(first, "old-first"), account(missing, "retained")),
                List.of(new PubgPlayer("retained", "first", List.of())));

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.failedMembers()).isEqualTo(2);
        assertNoAccountWrites();
    }

    @Test
    void cannotClaimAccountRetainedByLeftMember() {
        Community community = new Community("GuildUp");
        CommunityMember active = member(1L, community, "active");
        CommunityMember left = member(2L, community, "left");
        when(left.getStatus()).thenReturn(CommunityMemberStatus.LEFT);
        prepare(community, List.of(active), List.of(account(active, "old-active"), account(left, "retained")),
                List.of(new PubgPlayer("retained", "active", List.of())));

        assertThat(service.synchronize(5L, 10L, 20L).failedMembers()).isEqualTo(1);
        assertNoAccountWrites();
    }

    @Test
    void propagatesRetainedAccountConflictThroughReplacementChain() {
        Community community = new Community("GuildUp");
        CommunityMember first = member(1L, community, "first");
        CommunityMember second = member(2L, community, "second");
        CommunityMember missing = member(3L, community, "missing");
        prepare(community, List.of(first, second, missing),
                List.of(account(first, "account-1"), account(second, "account-2"), account(missing, "account-3")),
                List.of(new PubgPlayer("account-2", "first", List.of()),
                        new PubgPlayer("account-3", "second", List.of())));

        assertThat(service.synchronize(5L, 10L, 20L).failedMembers()).isEqualTo(3);
        assertNoAccountWrites();
    }

    @Test
    void allowsVerifiedAccountSwapWithoutDeletingUnresolvedMember() {
        Community community = new Community("GuildUp");
        CommunityMember first = member(1L, community, "first");
        CommunityMember second = member(2L, community, "second");
        CommunityMember missing = member(3L, community, "missing");
        CommunityMemberAccount firstAccount = account(first, "account-1");
        CommunityMemberAccount secondAccount = account(second, "account-2");
        prepare(community, List.of(first, second, missing),
                List.of(firstAccount, secondAccount, account(missing, "retained")),
                List.of(new PubgPlayer("account-2", "first", List.of()),
                        new PubgPlayer("account-1", "second", List.of())));

        var result = service.synchronize(5L, 10L, 20L);

        assertThat(result.synchronizedMembers()).isEqualTo(2);
        assertThat(result.updatedAccounts()).isEqualTo(2);
        assertThat(result.failedMembers()).isEqualTo(1);
        verifyReplacement(List.of(firstAccount, secondAccount), "account-2", "account-1");
    }

    @Test
    void lookupExceptionDoesNotUnlinkAnyExistingAccount() {
        Community community = new Community("GuildUp");
        CommunityMember first = member(1L, community, "first");
        prepare(community, List.of(first), List.of(account(first, "existing")), List.of());
        when(players.findByNamesFresh("kakao", List.of("first")))
                .thenThrow(new IllegalStateException("PUBG unavailable"));

        assertThatThrownBy(() -> service.synchronize(5L, 10L, 20L))
                .isInstanceOf(IllegalStateException.class);
        assertNoAccountWrites();
    }

    private CommunityMemberAccount account(CommunityMember member, String accountId) {
        return new CommunityMemberAccount(member, PubgPlatform.KAKAO, accountId, "stored-" + accountId);
    }

    private void prepare(Community community, List<CommunityMember> activeMembers,
                         List<CommunityMemberAccount> existingAccounts, List<PubgPlayer> resolvedPlayers) {
        CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);
        identify(community, game);
        when(games.findById(20L)).thenReturn(Optional.of(game));
        when(rules.findByCommunityIdAndGameType(10L, game.getGameType())).thenReturn(Optional.of(
                new CommunityGameNicknameRule(community, game.getGameType(), GameNicknameRuleStrategyType.FULL_NICKNAME,
                        null, null, false, null, "sample", "sample")));
        when(members.findByCommunityIdAndStatusOrderByIdAsc(10L, CommunityMemberStatus.ACTIVE)).thenReturn(activeMembers);
        when(accounts.findByCommunityIdAndProviderAndPlatform(10L, ExternalAccountProvider.PUBG, PubgPlatform.KAKAO))
                .thenReturn(existingAccounts);
        when(players.findByNamesFresh(org.mockito.ArgumentMatchers.eq("kakao"), anyList())).thenReturn(resolvedPlayers);
    }

    private void assertNoAccountWrites() {
        verify(accounts, never()).deleteAllInBatch(org.mockito.ArgumentMatchers.any());
        verify(accounts, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    private void verifyReplacement(List<CommunityMemberAccount> deleted, String... newAccountIds) {
        verify(accounts).deleteAllInBatch(org.mockito.ArgumentMatchers.argThat(values -> {
            var actual = new java.util.ArrayList<CommunityMemberAccount>(); values.forEach(actual::add);
            return actual.equals(deleted);
        }));
        verify(accounts).saveAll(org.mockito.ArgumentMatchers.argThat(values -> {
            var actual = new java.util.ArrayList<String>(); values.forEach(value -> actual.add(value.getExternalUserId()));
            return actual.equals(List.of(newAccountIds));
        }));
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
