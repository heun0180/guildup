package com.guildup.bingo;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoEventRequest;
import com.guildup.bingo.repository.*;
import com.guildup.bingo.service.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.*;
import com.guildup.pubg.repository.PubgStoredMatchRepository;
import com.guildup.pubg.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bingo-consistency;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Import(BingoAggregationFlowTests.ClockConfiguration.class)
@Transactional
class BingoAggregationConsistencyFlowTests {
    @Autowired BingoEventService eventService;
    @Autowired BingoAggregationService aggregation;
    @Autowired BingoEventRepository events;
    @Autowired BingoParticipantRepository participants;
    @Autowired BingoProgressRepository progress;
    @Autowired BingoLineCompletionRepository lines;
    @Autowired BingoProcessedMatchRepository processed;
    @Autowired PubgStoredMatchRepository stored;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired BingoAggregationFlowTests.MutableClock clock;
    @MockitoSpyBean PubgMatchFactWriter writer;
    @MockitoSpyBean BingoAggregationGuard guard;
    @MockitoBean PubgPlayerService players;
    @MockitoBean PubgMatchService matches;
    @MockitoBean com.guildup.bingo.mission.PubgBingoFactService facts;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    Long eventId;
    Community community;
    User owner;
    CommunityMember member;
    Instant matchAt;

    @BeforeEach void setup() {
        clock.set(Instant.parse("2026-09-22T12:00:00Z"));
        matchAt = clock.instant().minusSeconds(600);
        community = communities.save(new Community("정합성"));
        games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        owner = users.save(new User("참가자"));
        CommunityUser membership = new CommunityUser(community, owner, CommunityUserRole.OWNER);
        ReflectionTestUtils.setField(membership, "joinedAt", clock.instant().minusSeconds(7200));
        memberships.save(membership);
        userAccounts.save(new UserExternalAccount(owner, ExternalAccountProvider.DISCORD, "discord-consistency", "A"));
        member = members.save(new CommunityMember(community, "A"));
        accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "discord-consistency", "A"));
        accounts.save(new CommunityMemberAccount(member, PubgPlatform.KAKAO, "account-a", "A"));
        List<BingoEventRequest.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) cells.add(new BingoEventRequest.Cell(i, BingoMissionType.KILLS,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.valueOf(i < 3 ? 1 : 5), null, Map.of(), null));
        eventId = eventService.create(owner.getId(), community.getId(), new BingoEventRequest("정합성", null,
                clock.instant().minusSeconds(3600), clock.instant().plusSeconds(21600),
                3, 3, true, false, BingoStatus.ACTIVE, cells)).id();
        discover("good");
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("good", match("good")));
        when(facts.factsRequired(eq(PubgPlatform.KAKAO), any(), anySet())).thenAnswer(invocation -> {
            PubgMatch match = invocation.getArgument(1);
            return Map.of("account-a", fact(match.matchId(), 5));
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullReplayRevokesPartialTargetAndBlackoutAndRecreatesCorrectTimes(boolean personal) {
        aggregate(personal);
        assertDerived(8, matchAt, matchAt);
        replaceFact(1);
        aggregate(personal);
        assertDerived(1, null, null);
        assertThat(lines.findByParticipantId(participant().getId())).extracting(BingoLineCompletion::getLineKey)
                .containsExactly("ROW_0");
        replaceFact(0);
        aggregate(personal);
        assertDerived(0, null, null);
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()))
                .allSatisfy(row -> {
                    assertThat(row.isCompleted()).isFalse();
                    assertThat(row.getCompletedAt()).isNull();
                    assertThat(row.getEvidenceEventAt()).isNull();
                });
        matchAt = matchAt.plusSeconds(90);
        replaceFact(5);
        aggregate(personal);
        assertDerived(8, matchAt, matchAt);
        assertThat(lines.findByParticipantId(participant().getId()))
                .allSatisfy(line -> assertThat(line.getCompletedAt()).isEqualTo(matchAt));
        var before = participant().getTargetLinesCompletedAt();
        clock.set(clock.instant().plusSeconds(1800));
        aggregate(personal);
        assertDerived(8, before, before);
    }

    @Test void disconnectingAccountRebuildsPreviouslyCompletedProgress() {
        aggregate(false);
        var account = accounts.findByCommunityMemberIdAndProviderAndPlatform(member.getId(), ExternalAccountProvider.PUBG,
                PubgPlatform.KAKAO).orElseThrow();
        accounts.delete(account); accounts.flush();
        clock.set(clock.instant().plusSeconds(1800));
        aggregate(false);
        assertDerived(0, null, null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void decreasingReplayRemovesObsoleteLinesAndCompletionTimestamps(boolean personal) {
        aggregate(personal);
        assertThat(participant().getTargetLinesCompletedAt()).isNotNull();
        assertThat(participant().getBlackoutCompletedAt()).isNotNull();
        replaceFact(1);
        aggregate(personal);
        assertDerived(1, null, null);
        replaceFact(0);
        aggregate(personal);
        assertDerived(0, null, null);
    }

    @ParameterizedTest @ValueSource(strings = {"PLAYER", "PLAYER_THROW", "MATCH", "MATCH_THROW", "TELEMETRY", "MATCH_SAVE", "TELEMETRY_SAVE", "FACT"})
    void finalSettlementWaitsForEveryRequiredStageAndRetryCompletes(String failure) {
        aggregate(false);
        var oldValue = progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue();
        discover("good", "missing");
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("missing", match("missing")));
        switch (failure) {
            case "PLAYER" -> when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of());
            case "PLAYER_THROW" -> when(players.findByAccountIdsFresh(eq("kakao"), anyList()))
                    .thenThrow(new PubgApiException("player unavailable", null, 500, true));
            case "MATCH" -> when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of());
            case "MATCH_THROW" -> when(matches.findUniqueMatches(eq("kakao"), anyCollection()))
                    .thenThrow(new PubgApiException("matches unavailable", null, 500, true));
            case "TELEMETRY" -> when(facts.factsRequired(eq(PubgPlatform.KAKAO), argThat(m -> m.matchId().equals("missing")), anySet()))
                    .thenThrow(new PubgApiException("unavailable", null, 500, true));
            case "FACT" -> when(facts.factsRequired(eq(PubgPlatform.KAKAO), argThat(m -> m.matchId().equals("missing")), anySet()))
                    .thenReturn(Map.of());
            case "MATCH_SAVE" -> doThrow(new DataIntegrityViolationException("match save failed"))
                    .when(writer).saveMatchIfAbsent(eq(PubgPlatform.KAKAO), argThat(m -> m.matchId().equals("missing")));
            case "TELEMETRY_SAVE" -> doThrow(new DataIntegrityViolationException("telemetry save failed"))
                    .when(writer).saveTelemetry(eq(PubgPlatform.KAKAO), eq("missing"), anyMap());
        }
        var event = events.findById(eventId).orElseThrow();
        clock.set(event.getMatchStartUpperBoundExclusive().plus(BingoEvent.SETTLEMENT_GRACE));
        var result = aggregate(false);
        assertThat(result.status()).isEqualTo("SETTLING");
        var expectedStage = switch (failure) {
            case "PLAYER", "PLAYER_THROW" -> PubgMatchSyncService.FailureStage.PLAYER_LOOKUP;
            case "MATCH", "MATCH_THROW" -> PubgMatchSyncService.FailureStage.MATCH_LOOKUP;
            case "TELEMETRY" -> PubgMatchSyncService.FailureStage.TELEMETRY_LOOKUP;
            case "MATCH_SAVE" -> PubgMatchSyncService.FailureStage.MATCH_SAVE;
            case "TELEMETRY_SAVE" -> PubgMatchSyncService.FailureStage.TELEMETRY_SAVE;
            default -> PubgMatchSyncService.FailureStage.FACT_GENERATION;
        };
        assertThat(result.collectionFailures()).extracting(PubgMatchSyncService.CollectionFailure::stage).contains(expectedStage);
        assertThat(event.getCompletedAt()).isNull();
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue())
                .isEqualByComparingTo(oldValue);
        assertThat(processed.findDistinctMatchIdsByEventId(eventId)).doesNotContain("missing");
        reset(players, matches, facts, writer);
        // A failed Match must remain retryable even after it falls out of the latest Player window.
        if (failure.startsWith("PLAYER")) discover("good", "missing");
        else discover("good");
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("missing", match("missing")));
        when(facts.factsRequired(eq(PubgPlatform.KAKAO), any(), anySet())).thenReturn(Map.of("account-a", fact("missing", 5)));
        clock.set(clock.instant().plusSeconds(1800));
        var retried = aggregate(false);
        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(processed.findDistinctMatchIdsByEventId(eventId)).contains("missing");
        var completedAt = event.getCompletedAt();
        var finalValue = progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue();
        assertThatThrownBy(() -> aggregate(false)).isInstanceOf(ResponseStatusException.class);
        assertThat(event.getCompletedAt()).isEqualTo(completedAt);
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue())
                .isEqualByComparingTo(finalValue);
    }

    @Test void completeCollectionFinalizesSettlement() {
        clock.set(events.findById(eventId).orElseThrow().getMatchStartUpperBoundExclusive().plus(BingoEvent.SETTLEMENT_GRACE));
        assertThat(aggregate(false).status()).isEqualTo("COMPLETED");
        assertThat(events.findById(eventId).orElseThrow().getCompletedAt()).isEqualTo(clock.instant());
    }

    @Test void accountChangeDuringPreparationCannotProduceAnInternallyStaleSnapshot() {
        doAnswer(invocation -> {
            var account = accounts.findByCommunityMemberIdAndProviderAndPlatform(member.getId(), ExternalAccountProvider.PUBG,
                    PubgPlatform.KAKAO).orElseThrow();
            accounts.delete(account); accounts.flush();
            accounts.saveAndFlush(new CommunityMemberAccount(member, PubgPlatform.KAKAO, "replacement", "New"));
            return invocation.callRealMethod();
        }).when(guard).claim(any());
        assertThatThrownBy(() -> aggregate(false)).isInstanceOf(ResponseStatusException.class);
        assertThat(processed.findDistinctMatchIdsByEventId(eventId)).isEmpty();
        assertThat(participant().getLastAggregatedAt()).isNull();
    }

    @Test void missingPreviouslyCountedMatchRetriesOutsideTheLatestPlayerWindowWithoutErasingProgress() {
        aggregate(false);
        stored.delete(stored.findByShardAndMatchId("kakao", "good").orElseThrow()); stored.flush();
        discover();
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of());
        var event = events.findById(eventId).orElseThrow();
        clock.set(event.getMatchStartUpperBoundExclusive().plus(BingoEvent.SETTLEMENT_GRACE));
        assertThat(aggregate(false).status()).isEqualTo("SETTLING");
        assertThat(event.getPendingMatchIds()).contains("good");
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue())
                .isEqualByComparingTo("5");
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("good", match("good")));
        clock.set(clock.instant().plusSeconds(1800));
        assertThat(aggregate(false).status()).isEqualTo("COMPLETED");
        assertThat(event.getPendingMatchIds()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"CANCEL", "PERIOD", "BOTS", "CLAN", "ACCOUNT", "ELIGIBLE", "MEMBER", "PARTICIPANTS"})
    void changedInputsRejectOldWorkBeforeAnyProgressWrite(String change) {
        assertThatThrownBy(() -> aggregation.aggregate(owner.getId(), community.getId(), eventId,
                (stage, done, total, message) -> {
                    if (!stage.equals("CALCULATE_BINGO")) return;
                    mutate(change);
                })).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(processed.findDistinctMatchIdsByEventId(eventId)).isEmpty();
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()))
                .allSatisfy(row -> assertThat(row.getCurrentValue()).isZero());
        assertThat(participant().getLastAggregatedAt()).isNull();
        if (!change.equals("CANCEL")) {
            if (change.equals("MEMBER")) ReflectionTestUtils.setField(member, "status", CommunityMemberStatus.ACTIVE);
            assertThat(aggregate(false).status()).isIn("ACTIVE", "SETTLING");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void newerFullOrPersonalAggregationWinsEvenWhenOlderWorkFinishesLast(boolean newerPersonal) {
        AtomicBoolean started = new AtomicBoolean();
        assertThatThrownBy(() -> aggregation.aggregate(owner.getId(), community.getId(), eventId,
                (stage, done, total, message) -> {
                    if (!stage.equals("CALCULATE_BINGO") || !started.compareAndSet(false, true)) return;
                    writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match("new"));
                    writer.saveTelemetry(PubgPlatform.KAKAO, "new", Map.of("account-a", fact("new", 7)));
                    aggregate(newerPersonal);
                })).isInstanceOf(ResponseStatusException.class);
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant().getId()).getFirst().getCurrentValue())
                .isEqualByComparingTo("12");
        assertThat(processed.findDistinctMatchIdsByEventId(eventId)).contains("new");
    }

    void mutate(String change) {
        BingoEvent event = events.findById(eventId).orElseThrow();
        switch (change) {
            case "CANCEL" -> event.cancel(clock.instant());
            case "PERIOD" -> event.updateActive(null, event.getEndsAt().plusSeconds(3600), false, false, clock.instant());
            case "BOTS" -> event.updateActive(null, event.getEndsAt(), true, false, clock.instant());
            case "CLAN" -> event.updateActive(null, event.getEndsAt(), false, true, clock.instant());
            case "ACCOUNT" -> {
                var account = accounts.findByCommunityMemberIdAndProviderAndPlatform(member.getId(), ExternalAccountProvider.PUBG,
                        PubgPlatform.KAKAO).orElseThrow();
                accounts.delete(account); accounts.flush();
                accounts.saveAndFlush(new CommunityMemberAccount(member, PubgPlatform.KAKAO, "account-new", "New"));
            }
            case "ELIGIBLE" -> ReflectionTestUtils.setField(participant(), "eligibleFrom", matchAt.plusSeconds(1));
            case "MEMBER" -> ReflectionTestUtils.setField(member, "status", CommunityMemberStatus.LEFT);
            case "PARTICIPANTS" -> participants.saveAndFlush(new BingoParticipant(event,
                    memberships.save(new CommunityUser(community, users.save(new User("새 참가자")), CommunityUserRole.MEMBER)),
                    null, null, null, clock.instant(), clock.instant()));
        }
    }

    void discover(String... ids) {
        when(players.findByAccountIdsFresh(eq("kakao"), anyList()))
                .thenReturn(List.of(new PubgPlayer("account-a", "A", List.of(ids))));
    }
    PubgMatch match(String id) {
        return new PubgMatch(id, matchAt, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 5)))));
    }
    PlayerMatchFacts fact(String id, int kills) {
        return new PlayerMatchFacts(id, matchAt, "Erangel_Main", "squad", Map.of("KILLS", BigDecimal.valueOf(kills)),
                List.of(), Map.of(), 0, matchAt);
    }
    BingoParticipant participant() { return participants.findByEventIdOrderByIdAsc(eventId).getFirst(); }
    com.guildup.bingo.dto.BingoAggregationResponse aggregate(boolean personal) {
        return personal ? aggregation.aggregatePersonal(owner.getId(), community.getId(), eventId)
                : aggregation.aggregate(owner.getId(), community.getId(), eventId);
    }
    void replaceFact(int kills) {
        var match = stored.findByShardAndMatchId("kakao", "good").orElseThrow();
        ReflectionTestUtils.setField(match, "telemetryLoaded", false);
        stored.saveAndFlush(match);
        writer.saveTelemetry(PubgPlatform.KAKAO, "good", Map.of("account-a", fact("good", kills)));
        clock.set(clock.instant().plusSeconds(1800));
    }
    void assertDerived(int count, Instant target, Instant blackout) {
        var participant = participant();
        assertThat(participant.getLineCount()).isEqualTo(count);
        assertThat(lines.findByParticipantId(participant.getId())).hasSize(count);
        assertThat(participant.getTargetLinesCompletedAt()).isEqualTo(target);
        assertThat(participant.getBlackoutCompletedAt()).isEqualTo(blackout);
    }
}
