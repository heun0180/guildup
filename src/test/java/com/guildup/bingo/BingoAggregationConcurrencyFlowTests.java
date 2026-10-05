package com.guildup.bingo;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoEventRequest;
import com.guildup.bingo.repository.*;
import com.guildup.bingo.service.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 테스트 자체를 transaction으로 감싸지 않고 준비/수집/반영과 경쟁 변경을 실제 commit한다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bingo-concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Import(BingoAggregationFlowTests.ClockConfiguration.class)
class BingoAggregationConcurrencyFlowTests {
    @Autowired BingoEventService eventService;
    @Autowired BingoAggregationService aggregation;
    @Autowired BingoEventRepository events;
    @Autowired BingoParticipantRepository participants;
    @Autowired BingoProgressRepository progress;
    @Autowired BingoProcessedMatchRepository processed;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired PubgMatchFactWriter writer;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BingoAggregationFlowTests.MutableClock clock;
    @MockitoBean PubgPlayerService players;
    @MockitoBean PubgMatchService matches;
    @MockitoBean com.guildup.bingo.mission.PubgBingoFactService facts;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    TransactionTemplate tx;
    Long eventId, communityId, ownerId, memberId, participantId;
    String accountId, matchId;
    Instant matchAt;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(transactionManager);
        // 실제 DB 정밀도와 entity snapshot의 BigDecimal scale 차이도 함께 검증한다.
        clock.set(Instant.parse("2026-09-22T12:00:00.123456789Z"));
        matchAt = clock.instant().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String suffix = UUID.randomUUID().toString();
        accountId = "account-" + suffix; matchId = "match-" + suffix;
        tx.executeWithoutResult(status -> {
            Community community = communities.save(new Community("동시성")); communityId = community.getId();
            games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
            User owner = users.save(new User("참가자")); ownerId = owner.getId();
            CommunityUser membership = new CommunityUser(community, owner, CommunityUserRole.OWNER);
            ReflectionTestUtils.setField(membership, "joinedAt", clock.instant().minusSeconds(7200));
            memberships.save(membership);
            userAccounts.save(new UserExternalAccount(owner, ExternalAccountProvider.DISCORD, suffix, "A"));
            CommunityMember member = members.save(new CommunityMember(community, "A")); memberId = member.getId();
            accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, suffix, "A"));
            accounts.save(new CommunityMemberAccount(member, PubgPlatform.KAKAO, accountId, "A"));
            List<BingoEventRequest.Cell> cells = new ArrayList<>();
            for (int i = 0; i < 9; i++) cells.add(new BingoEventRequest.Cell(i, BingoMissionType.KILLS,
                    BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                    BigDecimal.valueOf(5), null, Map.of(), null));
            eventId = eventService.create(ownerId, communityId, new BingoEventRequest("동시성", null,
                    clock.instant().minusSeconds(3600), clock.instant().plusSeconds(7200), 3, 3,
                    true, true, BingoStatus.ACTIVE, cells)).id();
            participantId = participants.findByEventIdOrderByIdAsc(eventId).getFirst().getId();
        });
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> ids = invocation.getArgument(1);
            return ids.stream().map(id -> new PubgPlayer(id, "A", List.of(matchId))).toList();
        });
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of(matchId, match(matchId)));
        when(facts.factsRequired(eq(PubgPlatform.KAKAO), any(), anySet())).thenAnswer(invocation -> {
            PubgMatch match = invocation.getArgument(1);
            return Map.of(accountId, fact(match.matchId(), 5));
        });
    }

    @Test void unchangedInputsCommitAcrossIndependentTransactionsAndPermitAnotherReplay() {
        aggregate(false);
        assertValue("5");
        clock.set(clock.instant().plusSeconds(1801));
        aggregate(false);
        assertValue("5");
    }

    @Test void freshlyEnrolledLateParticipantDoesNotMakeAnUnchangedSnapshotStale() {
        tx.executeWithoutResult(status -> {
            var late = new CommunityUser(communities.findById(communityId).orElseThrow(),
                    users.save(new User("늦은 참가자")), CommunityUserRole.MEMBER);
            ReflectionTestUtils.setField(late, "joinedAt", clock.instant().minusSeconds(60));
            memberships.save(late);
        });
        aggregate(false);
        assertValue("5");
        assertThat(participants.findByEventIdOrderByIdAsc(eventId)).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"CANCEL", "PERIOD", "BOTS", "CLAN", "ACCOUNT", "ELIGIBLE", "MEMBER", "PARTICIPANTS"})
    void externalCollectionCannotCommitAfterConcurrentInputChange(String change) throws Exception {
        for (boolean personal : List.of(false, true)) {
            // 각각 새 fixture를 사용하여 전체/개인 경로를 모두 확인한다.
            if (personal) { reset(players, matches, facts); setup(); }
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var old = executor.submit(() -> personal
                        ? aggregation.aggregatePersonal(ownerId, communityId, eventId, (stage, done, total, message) ->
                            awaitCalculation(stage, entered, release))
                        : aggregation.aggregate(ownerId, communityId, eventId, (stage, done, total, message) ->
                            awaitCalculation(stage, entered, release)));
                try {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    tx.executeWithoutResult(status -> mutate(change));
                } finally { release.countDown(); }
                assertThatThrownBy(() -> old.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ResponseStatusException.class);
            }
            assertValue("0");
            assertThat(processed.findDistinctMatchIdsByEventId(eventId)).isEmpty();
            assertThat(participants.findById(participantId).orElseThrow().getLastAggregatedAt()).isNull();
            if (!change.equals("CANCEL")) {
                if (change.equals("MEMBER")) tx.executeWithoutResult(status ->
                        ReflectionTestUtils.setField(members.findById(memberId).orElseThrow(), "status", CommunityMemberStatus.ACTIVE));
                aggregate(false);
            }
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void newerAggregationCommitsBeforeOldWorkAndCannotBeOverwritten(boolean oldPersonal, boolean newPersonal) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> oldPersonal
                    ? aggregation.aggregatePersonal(ownerId, communityId, eventId, (stage, done, total, message) ->
                        awaitCalculation(stage, entered, release))
                    : aggregation.aggregate(ownerId, communityId, eventId, (stage, done, total, message) ->
                        awaitCalculation(stage, entered, release)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match(matchId + "-new"));
                writer.saveTelemetry(PubgPlatform.KAKAO, matchId + "-new", Map.of(accountId, fact(matchId + "-new", 7)));
                aggregate(newPersonal);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> old.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ResponseStatusException.class);
        }
        assertValue("12");
        clock.set(clock.instant().plusSeconds(1801));
        aggregate(oldPersonal);
        assertValue("12");
    }

    void mutate(String change) {
        BingoEvent event = events.findForUpdate(eventId).orElseThrow();
        switch (change) {
            case "CANCEL" -> event.cancel(clock.instant());
            case "PERIOD" -> event.updateActive(null, event.getEndsAt().plusSeconds(3600), false, false, clock.instant());
            case "BOTS" -> event.updateActive(null, event.getEndsAt(), true, false, clock.instant());
            case "CLAN" -> event.updateActive(null, event.getEndsAt(), false, true, clock.instant());
            case "ACCOUNT" -> {
                var account = accounts.findByCommunityMemberIdAndProviderAndPlatform(memberId, ExternalAccountProvider.PUBG, PubgPlatform.KAKAO).orElseThrow();
                accounts.delete(account); accounts.flush();
                accounts.save(new CommunityMemberAccount(members.findById(memberId).orElseThrow(), PubgPlatform.KAKAO, accountId + "-new", "New"));
            }
            case "ELIGIBLE" -> ReflectionTestUtils.setField(participants.findById(participantId).orElseThrow(), "eligibleFrom", matchAt.plusSeconds(1));
            case "MEMBER" -> ReflectionTestUtils.setField(members.findById(memberId).orElseThrow(), "status", CommunityMemberStatus.LEFT);
            case "PARTICIPANTS" -> participants.save(new BingoParticipant(event,
                    memberships.save(new CommunityUser(communities.findById(communityId).orElseThrow(),
                            users.save(new User("새 참가자")), CommunityUserRole.MEMBER)), null, null, null,
                    clock.instant(), clock.instant()));
        }
    }

    void awaitCalculation(String stage, CountDownLatch entered, CountDownLatch release) {
        if (!stage.equals("CALCULATE_BINGO")) return;
        entered.countDown();
        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    void aggregate(boolean personal) {
        if (personal) aggregation.aggregatePersonal(ownerId, communityId, eventId);
        else aggregation.aggregate(ownerId, communityId, eventId);
    }
    PubgMatch match(String id) {
        return new PubgMatch(id, matchAt, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant(accountId, "A", 5)))));
    }
    PlayerMatchFacts fact(String id, int kills) {
        return new PlayerMatchFacts(id, matchAt, "Erangel_Main", "squad", Map.of("KILLS", BigDecimal.valueOf(kills)), List.of(), Map.of(), 0, matchAt);
    }
    void assertValue(String expected) {
        tx.executeWithoutResult(status -> assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participantId))
                .allSatisfy(row -> assertThat(row.getCurrentValue()).isEqualByComparingTo(expected)));
    }
}
