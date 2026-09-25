package com.guildup.bingo;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoEventRequest;
import com.guildup.bingo.repository.*;
import com.guildup.bingo.service.BingoEventService;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.killcompetition.domain.KillCompetitionGameMode;
import com.guildup.killcompetition.dto.*;
import com.guildup.killcompetition.repository.*;
import com.guildup.killcompetition.service.*;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bingo-kill-competition;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@Import(BingoKillCompetitionFlowTests.ClockConfig.class)
class BingoKillCompetitionFlowTests {
    @Autowired BingoEventService bingos;
    @Autowired BingoEventRepository bingoEvents;
    @Autowired BingoParticipantRepository bingoParticipants;
    @Autowired BingoProgressRepository bingoProgress;
    @Autowired BingoLineCompletionRepository bingoLines;
    @Autowired BingoProcessedSourceRepository processedSources;
    @Autowired KillCompetitionService competitions;
    @Autowired KillCompetitionParticipationService participation;
    @Autowired KillCompetitionSettlementService settlements;
    @Autowired KillCompetitionRepository competitionRepository;
    @Autowired KillCompetitionMatchResultRepository matchResults;
    @Autowired CommunityScoreHistoryRepository scoreHistories;
    @Autowired CommunityMemberScoreRepository memberScores;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityGameNicknameRuleRepository nicknameRules;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityRepository communities;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    @MockitoBean KillCompetitionPubgAggregator aggregator;
    @MockitoBean PubgPlayerService pubgPlayers;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    Community community;
    CommunityGame game;
    Person owner;

    @BeforeEach
    void setUp() {
        processedSources.deleteAll(); bingoLines.deleteAll(); bingoProgress.deleteAll(); bingoParticipants.deleteAll(); bingoEvents.deleteAll();
        matchResults.deleteAll(); competitionRepository.deleteAll(); scoreHistories.deleteAll(); memberScores.deleteAll();
        memberAccounts.deleteAll(); members.deleteAll(); communityUsers.deleteAll(); nicknameRules.deleteAll(); games.deleteAll();
        communities.deleteAll(); userAccounts.deleteAll(); users.deleteAll(); reset(aggregator, pubgPlayers);
        clock.set(Instant.parse("2026-09-21T06:00:00Z"));
        community = communities.save(new Community("치즈 클랜"));
        game = games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        owner = person(community, "Apple", CommunityUserRole.OWNER);
    }

    @Test
    void interimDoesNotCountAndCompletedSoloResultCountsTiedWinnersOnlyOnceAndRecalculatesLines() {
        Person fox = person(community, "Fox", CommunityUserRole.MEMBER);
        Person loser = person(community, "Loser", CommunityUserRole.MEMBER);
        Long bingoId = createActiveBingo(owner, BigDecimal.ONE, clock.instant().plus(Duration.ofHours(4)));
        var started = startSolo(List.of(owner, fox, loser));
        mockKills(Map.of(owner.accountId(), 8, fox.accountId(), 8, loser.accountId(), 2));

        clock.set(started.startedAt().plus(Duration.ofMinutes(5)));
        settlements.calculateInterim(owner.user().getId(), community.getId(), started.id());
        assertProgress(bingoId, owner, BigDecimal.ZERO, false);

        clock.set(started.endsAt().plusSeconds(1));
        var completed = finalizeAndPublish(started.id());

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.finalStandings()).filteredOn(KillCompetitionDetailResponse.Standing::winner).hasSize(2);
        assertProgress(bingoId, owner, BigDecimal.ONE, true);
        assertProgress(bingoId, fox, BigDecimal.ONE, true);
        assertProgress(bingoId, loser, BigDecimal.ZERO, false);
        assertThat(participant(bingoId, owner).getLineCount()).isEqualTo(8);
        assertThat(processedSources.count()).isEqualTo(2);

        settlements.publishDueResult(started.id());
        assertProgress(bingoId, owner, BigDecimal.ONE, true);
        assertThat(processedSources.count()).isEqualTo(2);
    }

    @Test
    void secondFinalWinCompletesTargetTwo() {
        Long bingoId = createActiveBingo(owner, BigDecimal.valueOf(2), clock.instant().plus(Duration.ofHours(6)));
        var first = startSolo(List.of(owner));
        clock.set(first.endsAt().plusSeconds(1)); mockKills(Map.of(owner.accountId(), 3)); finalizeAndPublish(first.id());
        assertProgress(bingoId, owner, BigDecimal.ONE, false);

        var second = startSolo(List.of(owner));
        clock.set(second.endsAt().plusSeconds(1)); mockKills(Map.of(owner.accountId(), 4)); finalizeAndPublish(second.id());
        assertProgress(bingoId, owner, BigDecimal.valueOf(2), true);
    }

    @Test
    void deletingCompletedCompetitionRemovesBingoSourceProgressAndDerivedLines() {
        Long bingoId = createActiveBingo(owner, BigDecimal.ONE, clock.instant().plus(Duration.ofHours(4)));
        var started = startSolo(List.of(owner));
        clock.set(started.endsAt().plusSeconds(1));
        mockKills(Map.of(owner.accountId(), 5));
        var completed = finalizeAndPublish(started.id());
        assertProgress(bingoId, owner, BigDecimal.ONE, true);
        assertThat(participant(bingoId, owner).getLineCount()).isEqualTo(8);

        competitions.delete(owner.user().getId(), community.getId(), game.getId(), completed.id());

        assertProgress(bingoId, owner, BigDecimal.ZERO, false);
        assertThat(participant(bingoId, owner).getLineCount()).isZero();
        assertThat(bingoLines.findByParticipantId(participant(bingoId, owner).getId())).isEmpty();
        assertThat(processedSources.count()).isZero();
    }

    @Test
    void completedTeamResultCountsEveryWinningTeamMember() {
        Person fox = person(community, "Fox", CommunityUserRole.MEMBER);
        Person cheese = person(community, "Cheese", CommunityUserRole.MEMBER);
        Person jeolmi = person(community, "Jeolmi", CommunityUserRole.MEMBER);
        Long bingoId = createActiveBingo(owner, BigDecimal.ONE, clock.instant().plus(Duration.ofHours(4)));
        var created = competitions.create(owner.user().getId(), community.getId(), game.getId(),
                new KillCompetitionCreateRequest("팀 킬내기", KillCompetitionGameMode.DUO,
                        clock.instant().plus(Duration.ofMinutes(30))));
        List<Person> people = List.of(owner, fox, cheese, jeolmi);
        people.forEach(person -> participation.join(person.user().getId(), community.getId(), created.id()));
        var ready = competitions.closeRecruitment(owner.user().getId(), community.getId(), created.id());
        List<Long> ids = ready.participants().stream().map(KillCompetitionDetailResponse.Participant::participantId).toList();
        competitions.configureTeams(owner.user().getId(), community.getId(), created.id(),
                new KillCompetitionTeamRequest(2, List.of(
                        new KillCompetitionTeamRequest.TeamAssignment(ids.get(0), 1),
                        new KillCompetitionTeamRequest.TeamAssignment(ids.get(1), 1),
                        new KillCompetitionTeamRequest.TeamAssignment(ids.get(2), 2),
                        new KillCompetitionTeamRequest.TeamAssignment(ids.get(3), 2))));
        var started = competitions.start(owner.user().getId(), community.getId(), created.id());
        clock.set(started.endsAt().plusSeconds(1));
        mockKills(Map.of(owner.accountId(), 2, fox.accountId(), 1, cheese.accountId(), 8, jeolmi.accountId(), 7));
        finalizeAndPublish(started.id());

        assertProgress(bingoId, owner, BigDecimal.ZERO, false);
        assertProgress(bingoId, fox, BigDecimal.ZERO, false);
        assertProgress(bingoId, cheese, BigDecimal.ONE, true);
        assertProgress(bingoId, jeolmi, BigDecimal.ONE, true);
    }

    @Test
    void ignoresWinnerWhoIsNotABingoParticipant() {
        Long bingoId = createActiveBingo(owner, BigDecimal.ONE, clock.instant().plus(Duration.ofHours(4)));
        Person late = person(community, "Late", CommunityUserRole.MEMBER);
        var started = startSolo(List.of(late));
        clock.set(started.endsAt().plusSeconds(1)); mockKills(Map.of(late.accountId(), 5)); finalizeAndPublish(started.id());

        assertThat(bingoParticipants.findByEventIdAndCommunityUserUserId(bingoId, late.user().getId())).isEmpty();
        assertThat(processedSources.count()).isZero();
    }

    @Test
    void usesCompetitionEndTimeEvenWhenResultIsPublishedAfterBingoEndAndIgnoresOtherCommunities() {
        Long expiredBingoId = createActiveBingo(owner, BigDecimal.ONE, clock.instant().plus(Duration.ofMinutes(45)));

        Community other = communities.save(new Community("다른 클랜"));
        CommunityGame otherGame = games.save(new CommunityGame(other, GameType.BATTLEGROUNDS_KAKAO));
        Person otherOwner = person(other, "Other", CommunityUserRole.OWNER);
        Long otherBingoId = createActiveBingo(otherOwner, other, otherGame, BigDecimal.ONE,
                clock.instant().plus(Duration.ofHours(4)));

        var started = startSolo(List.of(owner));
        clock.set(started.endsAt().plusSeconds(1)); mockKills(Map.of(owner.accountId(), 5)); finalizeAndPublish(started.id());
        assertProgress(expiredBingoId, owner, BigDecimal.ONE, true);
        assertProgress(otherBingoId, otherOwner, BigDecimal.ZERO, false);
        assertThat(processedSources.count()).isEqualTo(1);
    }

    @Test
    void competitionEndedBeforeBingoStartIsNotCountedWhenPublishedDuringBingo() {
        var started = startSolo(List.of(owner));
        clock.set(started.endsAt().plusSeconds(1));
        Long bingoId = createBingo(owner, community, game, BigDecimal.ONE,
                started.endsAt().plusMillis(500), started.endsAt().plus(Duration.ofHours(2))).id();
        mockKills(Map.of(owner.accountId(), 5));

        finalizeAndPublish(started.id());

        assertProgress(bingoId, owner, BigDecimal.ZERO, false);
        assertThat(processedSources.count()).isZero();
    }

    private Long createActiveBingo(Person admin, BigDecimal target, Instant endsAt) {
        return createActiveBingo(admin, community, game, target, endsAt);
    }

    private Long createActiveBingo(Person admin, Community targetCommunity, CommunityGame targetGame,
                                   BigDecimal target, Instant endsAt) {
        return createBingo(admin, targetCommunity, targetGame, target,
                clock.instant().minus(Duration.ofMinutes(1)), endsAt).id();
    }

    private com.guildup.bingo.dto.BingoDetailResponse createBingo(
            Person admin, Community targetCommunity, CommunityGame targetGame, BigDecimal target,
            Instant startsAt, Instant endsAt) {
        List<BingoEventRequest.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) cells.add(new BingoEventRequest.Cell(i, BingoMissionType.KILL_BET_WIN,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                target, null, Map.of(), null));
        return bingos.create(admin.user().getId(), targetCommunity.getId(), targetGame.getId(),
                new BingoEventRequest("킬내기 빙고", null, startsAt, endsAt, 3, 1,
                        false, true, startsAt.isAfter(clock.instant()) ? BingoStatus.SCHEDULED : BingoStatus.ACTIVE, cells));
    }

    private KillCompetitionDetailResponse startSolo(List<Person> people) {
        var created = competitions.create(owner.user().getId(), community.getId(), game.getId(),
                new KillCompetitionCreateRequest("개인 킬내기", KillCompetitionGameMode.SOLO,
                        clock.instant().plus(Duration.ofMinutes(30))));
        people.forEach(person -> participation.join(person.user().getId(), community.getId(), created.id()));
        competitions.closeRecruitment(owner.user().getId(), community.getId(), created.id());
        return competitions.start(owner.user().getId(), community.getId(), created.id());
    }

    private KillCompetitionDetailResponse finalizeAndPublish(Long competitionId) {
        var pending = settlements.finalizeResult(owner.user().getId(), community.getId(), competitionId);
        clock.set(pending.resultPublishAt());
        settlements.publishDueResult(competitionId);
        return competitions.get(owner.user().getId(), community.getId(), competitionId);
    }

    private void mockKills(Map<String,Integer> killsByAccount) {
        when(aggregator.aggregate(anyString(), any(), any(), anyList())).thenAnswer(invocation -> {
            Instant startedAt = invocation.getArgument(1);
            List<KillCompetitionPubgAggregator.PlayerInput> inputs = invocation.getArgument(3);
            Map<Long,KillCompetitionKillSnapshot.PlayerTotal> totals = new LinkedHashMap<>();
            List<KillCompetitionKillSnapshot.MatchKill> rows = new ArrayList<>();
            for (var input : inputs) {
                int kills = killsByAccount.getOrDefault(input.accountId(), 0);
                totals.put(input.participantId(), new KillCompetitionKillSnapshot.PlayerTotal(kills, 1));
                rows.add(new KillCompetitionKillSnapshot.MatchKill(
                        input.participantId(), "match-" + input.participantId(), startedAt.plusSeconds(1), kills));
            }
            return new KillCompetitionKillSnapshot(totals, rows);
        });
    }

    private void assertProgress(Long bingoId, Person person, BigDecimal value, boolean completed) {
        var row = bingoProgress.findByParticipantIdOrderByCellPositionAsc(participant(bingoId, person).getId()).getFirst();
        assertThat(row.getCurrentValue()).isEqualByComparingTo(value);
        assertThat(row.isCompleted()).isEqualTo(completed);
    }

    private BingoParticipant participant(Long bingoId, Person person) {
        return bingoParticipants.findByEventIdAndCommunityUserUserId(bingoId, person.user().getId()).orElseThrow();
    }

    private Person person(Community targetCommunity, String name, CommunityUserRole role) {
        User user = users.save(new User(name));
        String externalId = "discord-" + targetCommunity.getId() + "-" + name;
        userAccounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, externalId, name));
        communityUsers.save(new CommunityUser(targetCommunity, user, role));
        CommunityMember member = members.save(new CommunityMember(targetCommunity, name));
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, externalId, name));
        String accountId = "account-" + targetCommunity.getId() + "-" + name;
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.PUBG, accountId, "pubg-" + name));
        return new Person(user, member, accountId);
    }

    private record Person(User user, CommunityMember member, String accountId) {}

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
