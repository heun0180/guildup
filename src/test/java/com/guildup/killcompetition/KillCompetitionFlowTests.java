package com.guildup.killcompetition;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.killcompetition.domain.KillCompetitionGameMode;
import com.guildup.killcompetition.dto.*;
import com.guildup.killcompetition.repository.*;
import com.guildup.killcompetition.service.*;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import net.dv8tion.jda.api.JDA;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kill-competition;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@Import(KillCompetitionFlowTests.ClockConfig.class)
class KillCompetitionFlowTests {
    @Autowired KillCompetitionService competitions;
    @Autowired KillCompetitionParticipationService participation;
    @Autowired KillCompetitionSettlementService settlements;
    @Autowired KillCompetitionSettlementStore settlementStore;
    @Autowired KillCompetitionRepository competitionRepository;
    @Autowired KillCompetitionMatchResultRepository matchResults;
    @Autowired CommunityScoreHistoryRepository histories;
    @Autowired CommunityMemberScoreRepository scores;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityGameRepository communityGames;
    @Autowired CommunityGameNicknameRuleRepository nicknameRules;
    @Autowired CommunityRepository communities;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    @MockitoBean KillCompetitionPubgAggregator aggregator;
    @MockitoBean PubgPlayerService pubgPlayers;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    Community community;
    Person creator;

    @BeforeEach
    void setUp() {
        matchResults.deleteAll(); competitionRepository.deleteAll(); histories.deleteAll(); scores.deleteAll();
        memberAccounts.deleteAll(); members.deleteAll(); communityUsers.deleteAll(); nicknameRules.deleteAll(); communityGames.deleteAll();
        communities.deleteAll(); userAccounts.deleteAll(); users.deleteAll(); reset(aggregator, pubgPlayers);
        clock.set(Instant.parse("2026-09-15T12:00:00Z"));
        community = communities.save(new Community("치즈 클랜"));
        communityGames.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        creator = person("생성자", CommunityUserRole.MEMBER, true);
    }

    @Test
    void memberCreatesWithoutAutomaticParticipationAndParticipantCanJoinCancelAndDuplicateIsRejected() {
        var created = create(KillCompetitionGameMode.SOLO);
        assertThat(created.creatorView()).isTrue();
        assertThat(created.participants()).isEmpty();

        var joined = participation.join(creator.user().getId(), community.getId(), created.id());
        assertThat(joined.participants()).singleElement().extracting(KillCompetitionDetailResponse.Participant::pubgNickname)
                .isEqualTo("pubg-생성자");
        assertConflict(() -> participation.join(creator.user().getId(), community.getId(), created.id()));
        assertThat(competitions.leave(creator.user().getId(), community.getId(), created.id()).participants()).isEmpty();
        assertConflict(() -> competitions.leave(creator.user().getId(), community.getId(), created.id()));
    }

    @Test
    void nicknameIsRequiredAndRecruitmentCloseStartsSoloWithServerTime() {
        Person noPubg = person("닉네임없음", CommunityUserRole.MEMBER, false);
        var created = create(KillCompetitionGameMode.SOLO);
        assertConflict(() -> participation.join(noPubg.user().getId(), community.getId(), created.id()));
        participation.join(creator.user().getId(), community.getId(), created.id());
        competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        clock.set(Instant.parse("2026-09-15T12:17:32Z"));
        var started = competitions.start(creator.user().getId(), community.getId(), created.id());
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(started.startedAt()).isEqualTo(clock.instant());
        assertConflict(() -> competitions.leave(creator.user().getId(), community.getId(), created.id()));
    }

    @Test
    void missingPubgAccountIsResolvedFromCommunityNicknameRuleWhenJoining() {
        Person apple = person("치즈/93/TEMA-___-", CommunityUserRole.MEMBER, false);
        nicknameRules.save(new CommunityGameNicknameRule(
                community, GameType.BATTLEGROUNDS_KAKAO,
                GameNicknameRuleStrategyType.DELIMITED_SEGMENT, GameNicknameDelimiterType.SLASH,
                0, true, 3, "치즈/93/TEMA-___-", "TEMA-___-"));
        when(pubgPlayers.findByNamesFresh("kakao", List.of("TEMA-___-")))
                .thenReturn(List.of(new PubgPlayer("account-apple", "TEMA-___-", List.of())));
        var created = create(KillCompetitionGameMode.SOLO);

        assertThat(competitions.get(apple.user().getId(), community.getId(), created.id())
                .pubgNicknameConfigured()).isTrue();
        var joined = participation.join(apple.user().getId(), community.getId(), created.id());

        assertThat(joined.participants()).singleElement()
                .extracting(KillCompetitionDetailResponse.Participant::pubgNickname)
                .isEqualTo("TEMA-___-");
        assertThat(memberAccounts.findByCommunityMemberIdAndProvider(
                apple.member().getId(), ExternalAccountProvider.PUBG)).get().satisfies(account -> {
                    assertThat(account.getExternalUserId()).isEqualTo("account-apple");
                    assertThat(account.getExternalUsername()).isEqualTo("TEMA-___-");
                });
        verify(pubgPlayers).findByNamesFresh("kakao", List.of("TEMA-___-"));
    }

    @Test
    void unresolvedPubgNicknameDoesNotCreateAccountOrParticipant() {
        Person apple = person("치즈/93/unknown-player", CommunityUserRole.MEMBER, false);
        nicknameRules.save(new CommunityGameNicknameRule(
                community, GameType.BATTLEGROUNDS_KAKAO,
                GameNicknameRuleStrategyType.DELIMITED_SEGMENT, GameNicknameDelimiterType.SLASH,
                0, true, 3, "치즈/93/sample", "sample"));
        when(pubgPlayers.findByNamesFresh("kakao", List.of("unknown-player"))).thenReturn(List.of());
        var created = create(KillCompetitionGameMode.SOLO);

        assertConflict(() -> participation.join(apple.user().getId(), community.getId(), created.id()));

        assertThat(memberAccounts.findByCommunityMemberIdAndProvider(
                apple.member().getId(), ExternalAccountProvider.PUBG)).isEmpty();
        assertThat(competitions.get(creator.user().getId(), community.getId(), created.id()).participants()).isEmpty();
    }

    @Test
    void teamModeRequiresEveryParticipantOnceAndAllowsReconfigurationBeforeStart() {
        Person second = person("둘", CommunityUserRole.MEMBER, true);
        Person third = person("셋", CommunityUserRole.MEMBER, true);
        var created = create(KillCompetitionGameMode.SQUAD);
        for (Person person : List.of(creator, second, third)) participation.join(person.user().getId(), community.getId(), created.id());
        var ready = competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        assertConflict(() -> competitions.start(creator.user().getId(), community.getId(), created.id()));
        List<Long> ids = ready.participants().stream().map(KillCompetitionDetailResponse.Participant::participantId).toList();
        assertBadRequest(() -> competitions.configureTeams(creator.user().getId(), community.getId(), created.id(),
                new KillCompetitionTeamRequest(2, List.of(new KillCompetitionTeamRequest.TeamAssignment(ids.getFirst(), 1)))));
        var configured = competitions.configureTeams(creator.user().getId(), community.getId(), created.id(),
                teamRequest(ids, 1, 1, 2));
        assertThat(configured.teams()).hasSize(2);
        configured = competitions.configureTeams(creator.user().getId(), community.getId(), created.id(),
                teamRequest(ids, 2, 1, 1));
        assertThat(configured.participants()).allMatch(p -> p.teamId() != null);
        assertThat(competitions.start(creator.user().getId(), community.getId(), created.id()).status())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void cannotStartAfterEndTime() {
        var created = create(KillCompetitionGameMode.SOLO);
        participation.join(creator.user().getId(), community.getId(), created.id());
        competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        clock.set(created.endsAt());
        assertConflict(() -> competitions.start(creator.user().getId(), community.getId(), created.id()));
    }

    @Test
    void interimRecalculatesSnapshotHasCompetitionWideCooldownAndDoesNotComplete() {
        var started = startedSolo(List.of(creator));
        mockKills(Map.of("account-생성자", 4));
        var interim = settlements.calculateInterim(creator.user().getId(), community.getId(), started.id());
        assertThat(interim.interimStandings()).singleElement().extracting(KillCompetitionDetailResponse.Standing::kills).isEqualTo(4);
        assertThat(interim.status()).isEqualTo("IN_PROGRESS");
        assertThat(interim.finalStandings()).isEmpty();
        assertConflict(() -> settlements.calculateInterim(creator.user().getId(), community.getId(), started.id()));
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        mockKills(Map.of("account-생성자", 7));
        assertThat(settlements.calculateInterim(creator.user().getId(), community.getId(), started.id())
                .interimStandings().getFirst().kills()).isEqualTo(7);
    }

    @Test
    void interimClaimIsSharedByTheCompetitionAcrossParticipants() {
        Person second = person("둘", CommunityUserRole.MEMBER, true);
        var started = startedSolo(List.of(creator, second));
        var work = settlementStore.claimInterim(creator.user().getId(), community.getId(), started.id());
        assertConflict(() -> settlementStore.claimInterim(second.user().getId(), community.getId(), started.id()));
        settlementStore.releaseInterim(community.getId(), work);
        assertThat(settlementStore.claimInterim(second.user().getId(), community.getId(), started.id())).isNotNull();
    }

    @Test
    void soloFinalAwardsThreePointsOnceWithFourParticipantsAndStoresMatchRows() {
        List<Person> people = new ArrayList<>(List.of(creator));
        people.add(person("둘", CommunityUserRole.MEMBER, true));
        people.add(person("셋", CommunityUserRole.MEMBER, true));
        people.add(person("넷", CommunityUserRole.MEMBER, true));
        var started = startedSolo(people);
        clock.set(started.endsAt().plusSeconds(1));
        mockKills(Map.of("account-생성자", 9, "account-둘", 4, "account-셋", 3, "account-넷", 2));
        var completed = finalizeAndPublish(started.id());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.finalStandings().getFirst()).satisfies(row -> {
            assertThat(row.name()).isEqualTo("생성자"); assertThat(row.winner()).isTrue(); assertThat(row.rank()).isEqualTo(1);
        });
        assertThat(scores.findByCommunityMemberId(creator.member().getId()).orElseThrow().getTotalScore()).isEqualTo(3);
        assertThat(matchResults.findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(started.id())).hasSize(4);
        settlements.publishDueResult(started.id());
        assertConflict(() -> settlements.finalizeResult(creator.user().getId(), community.getId(), started.id()));
        assertThat(histories.findByCommunityMemberIdOrderByCreatedAtDescIdDesc(creator.member().getId())).hasSize(1);
    }

    @Test
    void teamFinalAwardsEveryMemberOfWinningTeam() {
        List<Person> people = new ArrayList<>(List.of(creator));
        people.add(person("둘", CommunityUserRole.MEMBER, true));
        people.add(person("셋", CommunityUserRole.MEMBER, true));
        people.add(person("넷", CommunityUserRole.MEMBER, true));
        var created = competitions.create(creator.user().getId(), community.getId(), new KillCompetitionCreateRequest(
                "듀오 킬내기", KillCompetitionGameMode.DUO, clock.instant().plus(Duration.ofMinutes(30))));
        people.forEach(person -> participation.join(person.user().getId(), community.getId(), created.id()));
        var ready = competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        List<Long> ids = ready.participants().stream().map(KillCompetitionDetailResponse.Participant::participantId).toList();
        competitions.configureTeams(creator.user().getId(), community.getId(), created.id(), teamRequest(ids, 1, 1, 2, 2));
        var started = competitions.start(creator.user().getId(), community.getId(), created.id());
        clock.set(started.endsAt().plusSeconds(1));
        mockKills(Map.of("account-생성자", 1, "account-둘", 1, "account-셋", 8, "account-넷", 7));

        var completed = finalizeAndPublish(started.id());
        Long winningTeam = completed.finalStandings().stream().filter(KillCompetitionDetailResponse.Standing::winner)
                .map(KillCompetitionDetailResponse.Standing::teamId).findFirst().orElseThrow();
        List<Long> winningMembers = completed.participants().stream().filter(p -> Objects.equals(p.teamId(), winningTeam))
                .map(KillCompetitionDetailResponse.Participant::memberId).toList();
        assertThat(winningMembers).hasSize(2);
        assertThat(people).filteredOn(person -> winningMembers.contains(person.member().getId()))
                .allSatisfy(person -> assertThat(scores.findByCommunityMemberId(person.member().getId()).orElseThrow().getTotalScore()).isEqualTo(3));
        assertThat(people).filteredOn(person -> !winningMembers.contains(person.member().getId()))
                .allSatisfy(person -> assertThat(scores.findByCommunityMemberId(person.member().getId())).isEmpty());
    }

    @Test
    void tiesAwardAllWinnersButDailyLimitBlocksSecondWinAndResetsNextDay() {
        List<Person> people = new ArrayList<>(List.of(creator));
        people.add(person("둘", CommunityUserRole.MEMBER, true));
        people.add(person("셋", CommunityUserRole.MEMBER, true));
        people.add(person("넷", CommunityUserRole.MEMBER, true));
        var first = startedSolo(people);
        clock.set(first.endsAt().plusSeconds(1));
        mockKills(Map.of("account-생성자", 10, "account-둘", 10, "account-셋", 2, "account-넷", 1));
        var completed = finalizeAndPublish(first.id());
        assertThat(completed.finalStandings()).filteredOn(KillCompetitionDetailResponse.Standing::winner).hasSize(2)
                .allMatch(row -> row.rank() == 1);

        var second = startedSolo(people);
        clock.set(second.endsAt().plusSeconds(1)); mockKills(Map.of("account-생성자", 20));
        finalizeAndPublish(second.id());
        assertThat(scores.findByCommunityMemberId(creator.member().getId()).orElseThrow().getTotalScore()).isEqualTo(3);

        clock.set(Instant.parse("2026-09-16T15:01:00Z"));
        var third = startedSolo(people);
        clock.set(third.endsAt().plusSeconds(1)); mockKills(Map.of("account-생성자", 30));
        finalizeAndPublish(third.id());
        assertThat(scores.findByCommunityMemberId(creator.member().getId()).orElseThrow().getTotalScore()).isEqualTo(6);
    }

    @Test
    void fewerThanFourGetsNoScoreAndPubgFailureLeavesResultUnconfirmed() {
        var small = startedSolo(List.of(creator));
        clock.set(small.endsAt().plusSeconds(1)); mockKills(Map.of("account-생성자", 5));
        assertThat(finalizeAndPublish(small.id()).status()).isEqualTo("COMPLETED");
        assertThat(scores.findByCommunityMemberId(creator.member().getId())).isEmpty();

        var failed = startedSolo(List.of(creator));
        clock.set(failed.endsAt().plusSeconds(1));
        when(aggregator.aggregate(anyString(), any(), any(), anyList())).thenThrow(new PubgApiException("실패"));
        settlements.finalizeResult(creator.user().getId(), community.getId(), failed.id());
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        settlements.publishDueResult(failed.id());
        assertThat(competitions.get(creator.user().getId(), community.getId(), failed.id()).status()).isEqualTo("RESULT_PENDING");
        assertThat(matchResults.findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(failed.id())).isEmpty();
    }

    @Test
    void inProgressJoinWaitsForCreatorApprovalAndUsesApprovalTime() {
        var started = startedSolo(List.of(creator));
        Person late = person("늦참", CommunityUserRole.MEMBER, true);
        clock.set(started.startedAt().plus(Duration.ofMinutes(5)));

        var pending = participation.join(late.user().getId(), community.getId(), started.id());
        var applicant = pending.participants().stream().filter(p -> p.memberId().equals(late.member().getId())).findFirst().orElseThrow();
        assertThat(applicant.participationStatus().name()).isEqualTo("PENDING");
        assertThat(applicant.eligibleFrom()).isNull();

        clock.set(clock.instant().plusSeconds(30));
        var approved = competitions.approveParticipant(creator.user().getId(), community.getId(), started.id(),
                applicant.participantId(), null);
        var participant = approved.participants().stream().filter(p -> p.memberId().equals(late.member().getId())).findFirst().orElseThrow();
        assertThat(participant.eligibleFrom()).isEqualTo(clock.instant());
        assertThat(participant.participationStatus().name()).isEqualTo("APPROVED");
    }

    @Test
    void recruitmentSwitchBlocksInProgressApplications() {
        var started = startedSolo(List.of(creator));
        Person late = person("신청자", CommunityUserRole.MEMBER, true);
        competitions.setRecruitment(creator.user().getId(), community.getId(), started.id(), false);
        assertConflict(() -> participation.join(late.user().getId(), community.getId(), started.id()));
        competitions.setRecruitment(creator.user().getId(), community.getId(), started.id(), true);
        assertThat(participation.join(late.user().getId(), community.getId(), started.id()).participants())
                .anyMatch(p -> p.memberId().equals(late.member().getId()));
    }

    @Test
    void duoLateApplicantRequiresCreatorAndInitialTeamAndCannotMoveAfterARecognizedMatch() {
        Person second = person("둘", CommunityUserRole.MEMBER, true);
        Person late = person("늦참팀", CommunityUserRole.MEMBER, true);
        var created = create(KillCompetitionGameMode.DUO);
        participation.join(creator.user().getId(), community.getId(), created.id());
        participation.join(second.user().getId(), community.getId(), created.id());
        var ready = competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        var ids = ready.participants().stream().map(KillCompetitionDetailResponse.Participant::participantId).toList();
        var configured = competitions.configureTeams(creator.user().getId(), community.getId(), created.id(), teamRequest(ids, 1, 2));
        var started = competitions.start(creator.user().getId(), community.getId(), created.id());
        var pending = participation.join(late.user().getId(), community.getId(), started.id()).participants().stream()
                .filter(p -> p.memberId().equals(late.member().getId())).findFirst().orElseThrow();

        assertThatThrownBy(() -> competitions.approveParticipant(second.user().getId(), community.getId(), started.id(),
                pending.participantId(), configured.teams().getFirst().teamId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        assertBadRequest(() -> competitions.approveParticipant(creator.user().getId(), community.getId(), started.id(),
                pending.participantId(), null));
        var approved = competitions.approveParticipant(creator.user().getId(), community.getId(), started.id(),
                pending.participantId(), configured.teams().getFirst().teamId());
        assertThat(approved.participants()).filteredOn(p -> p.participantId().equals(pending.participantId()))
                .singleElement().extracting(KillCompetitionDetailResponse.Participant::teamId)
                .isEqualTo(configured.teams().getFirst().teamId());

        mockKills(Map.of("account-생성자", 1, "account-둘", 1, "account-늦참팀", 1));
        settlements.calculateInterim(creator.user().getId(), community.getId(), started.id());
        assertConflict(() -> competitions.changeParticipantTeam(creator.user().getId(), community.getId(), started.id(),
                pending.participantId(), configured.teams().get(1).teamId()));
    }

    @Test
    void resultRequestDoesNotCallPubgAndSchedulerPublishesOnlyAfterThirtyMinutes() {
        var started = startedSolo(List.of(creator));
        clock.set(started.endsAt().plusSeconds(1));
        mockKills(Map.of("account-생성자", 3));

        var pending = settlements.finalizeResult(creator.user().getId(), community.getId(), started.id());
        assertThat(pending.status()).isEqualTo("RESULT_PENDING");
        assertThat(pending.resultPublishAt()).isEqualTo(pending.resultRequestedAt().plus(Duration.ofMinutes(30)));
        verifyNoInteractions(aggregator);
        assertConflict(() -> settlements.finalizeResult(creator.user().getId(), community.getId(), started.id()));

        clock.set(pending.resultPublishAt().minusSeconds(1));
        settlements.publishDueResult(started.id());
        verifyNoInteractions(aggregator);
        clock.set(pending.resultPublishAt());
        settlements.publishDueResult(started.id());
        verify(aggregator, times(1)).aggregate(anyString(), any(), any(), anyList());
        settlements.publishDueResult(started.id());
        verify(aggregator, times(1)).aggregate(anyString(), any(), any(), anyList());
        assertThat(competitions.get(creator.user().getId(), community.getId(), started.id()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void otherCommunityCannotReadCompetitionAndAdminCanCancel() {
        var created = create(KillCompetitionGameMode.SOLO);
        Community foreign = communities.save(new Community("다른 클랜"));
        communityGames.save(new CommunityGame(foreign, GameType.BATTLEGROUNDS_KAKAO));
        User outsider = users.save(new User("외부인"));
        communityUsers.save(new CommunityUser(foreign, outsider, CommunityUserRole.OWNER));
        assertThatThrownBy(() -> competitions.get(outsider.getId(), community.getId(), created.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        Person admin = person("관리자", CommunityUserRole.ADMIN, true);
        assertThat(competitions.cancel(admin.user().getId(), community.getId(), created.id()).status()).isEqualTo("CANCELLED");
    }

    private KillCompetitionDetailResponse create(KillCompetitionGameMode mode) {
        return competitions.create(creator.user().getId(), community.getId(), new KillCompetitionCreateRequest(
                "치즈 킬내기", mode, clock.instant().plus(Duration.ofMinutes(30))));
    }
    private KillCompetitionDetailResponse startedSolo(List<Person> people) {
        var created = create(KillCompetitionGameMode.SOLO);
        people.forEach(p -> participation.join(p.user().getId(), community.getId(), created.id()));
        competitions.closeRecruitment(creator.user().getId(), community.getId(), created.id());
        return competitions.start(creator.user().getId(), community.getId(), created.id());
    }
    private KillCompetitionDetailResponse finalizeAndPublish(Long competitionId) {
        var pending = settlements.finalizeResult(creator.user().getId(), community.getId(), competitionId);
        assertThat(pending.status()).isEqualTo("RESULT_PENDING");
        clock.set(pending.resultPublishAt());
        settlements.publishDueResult(competitionId);
        return competitions.get(creator.user().getId(), community.getId(), competitionId);
    }
    private KillCompetitionTeamRequest teamRequest(List<Long> ids, int... teamNumbers) {
        List<KillCompetitionTeamRequest.TeamAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) assignments.add(new KillCompetitionTeamRequest.TeamAssignment(ids.get(i), teamNumbers[i]));
        return new KillCompetitionTeamRequest(2, assignments);
    }
    private Person person(String name, CommunityUserRole role, boolean pubg) {
        User user = users.save(new User(name));
        userAccounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "discord-" + name, name));
        communityUsers.save(new CommunityUser(community, user, role));
        CommunityMember member = members.save(new CommunityMember(community, name));
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "discord-" + name, name));
        if (pubg) memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.PUBG, "account-" + name, "pubg-" + name));
        return new Person(user, member);
    }
    private void mockKills(Map<String, Integer> byAccount) {
        when(aggregator.aggregate(anyString(), any(), any(), anyList())).thenAnswer(invocation -> {
            Instant startedAt = invocation.getArgument(1); List<KillCompetitionPubgAggregator.PlayerInput> inputs = invocation.getArgument(3);
            Map<Long, KillCompetitionKillSnapshot.PlayerTotal> totals = new LinkedHashMap<>();
            List<KillCompetitionKillSnapshot.MatchKill> rows = new ArrayList<>();
            for (var input : inputs) {
                int kills = byAccount.getOrDefault(input.accountId(), 0);
                totals.put(input.participantId(), new KillCompetitionKillSnapshot.PlayerTotal(kills, 1));
                rows.add(new KillCompetitionKillSnapshot.MatchKill(input.participantId(), "match-1", startedAt.plusSeconds(1), kills));
            }
            return new KillCompetitionKillSnapshot(totals, rows);
        });
    }
    private void assertConflict(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
    private void assertBadRequest(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }
    private record Person(User user, CommunityMember member) {}
    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
