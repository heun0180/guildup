package com.guildup.bingo;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.*;
import com.guildup.bingo.mission.*;
import com.guildup.bingo.repository.*;
import com.guildup.bingo.service.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:temporary-bingo-rebuild;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa",
        "spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Import(TemporaryBingoRebuildFlowTests.ClockConfiguration.class)
class TemporaryBingoRebuildFlowTests {
    private static final AtomicInteger IDS = new AtomicInteger();
    @Autowired TemporaryBingoRebuildService rebuild;
    @Autowired BingoAggregationService aggregation;
    @Autowired BingoEventService eventService;
    @Autowired BingoEventRepository events;
    @Autowired BingoParticipantRepository participants;
    @Autowired BingoProgressRepository progress;
    @Autowired BingoProcessedMatchRepository processed;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired KillCompetitionRepository competitions;
    @Autowired MutableClock clock;
    @MockitoBean PubgPlayerService pubgPlayers;
    @MockitoBean PubgMatchService pubgMatches;
    @MockitoBean PubgBingoFactService factService;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    private Community community;
    private CommunityGame game;
    private User owner;
    private CommunityMember member;

    @BeforeEach
    void setUp() {
        reset(pubgPlayers, pubgMatches, factService);
        clock.set(Instant.parse("2026-09-24T03:00:00Z"));
        int id = IDS.incrementAndGet();
        community = communities.save(new Community("재집계-" + id));
        game = games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        owner = users.save(new User("owner-" + id));
        CommunityUser membership = new CommunityUser(community, owner, CommunityUserRole.OWNER);
        ReflectionTestUtils.setField(membership, "joinedAt", clock.instant().minus(Duration.ofDays(1)));
        memberships.save(membership);
        userAccounts.save(new UserExternalAccount(owner, ExternalAccountProvider.DISCORD, "discord-" + id, "owner"));
        member = members.save(new CommunityMember(community, "owner"));
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "discord-" + id, "owner"));
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.PUBG, "account-a", "Apple"));
    }

    @Test
    void previewAndApplyUpdatesOnlyDifferentMissionsAndKeepsProcessedLedger() {
        BingoEvent event = createEvent();
        BingoParticipant participant = participant(event);
        setOldProgress(participant, BingoMissionType.LONG_DISTANCE_KILL, 5, true);
        setOldProgress(participant, BingoMissionType.WEAPON_KILLS, 0, false);
        setOldProgress(participant, BingoMissionType.KILLS, 50, true);
        saveProcessed(event, participant, "M1", 1);
        saveProcessed(event, participant, "M2", 2);
        saveProcessed(event, participant, "M3", 3);
        completeWinningKillCompetition();

        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M2", "M3"))));
        Map<String, PubgMatch> matchRows = Map.of("M1", match("M1", 1), "M2", match("M2", 2), "M3", match("M3", 3));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String id = ((List<String>) invocation.getArgument(1)).getFirst();
            return Map.of(id, matchRows.get(id));
        });
        when(factService.factsRequired(any(), anySet())).thenAnswer(invocation -> {
            PubgMatch match = invocation.getArgument(0);
            return Map.of("account-a", facts(match.matchId(), match.playedAt()));
        });

        TemporaryBingoRebuildResponse preview = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(preview.status()).isEqualTo("REBUILD_PREVIEW_READY");
        assertThat(preview.matchCount()).isEqualTo(3);
        assertThat(preview.participants()).singleElement().satisfies(result -> {
            assertChange(result, BingoMissionType.LONG_DISTANCE_KILL, "5", "1", false);
            assertChange(result, BingoMissionType.WEAPON_KILLS, "0", "2", false);
            assertChange(result, BingoMissionType.KILL_BET_WIN, "0", "1", true);
            assertThat(result.changes()).noneMatch(change -> change.missionType().equals(BingoMissionType.KILLS.name()));
            assertThat(result.changes()).hasSize(15);
        });
        assertThat(value(participant, BingoMissionType.LONG_DISTANCE_KILL)).isEqualByComparingTo("5");
        assertThat(processed.findMatchIds(event.getId(), participant.getId()))
                .containsExactlyInAnyOrder("M1", "M2", "M3");
        Instant unchangedKillsUpdatedAt = row(participant, BingoMissionType.KILLS).getUpdatedAt();

        TemporaryBingoRebuildResponse applied = rebuild.apply(owner.getId(), community.getId(), game.getId(), preview.previewToken());

        assertThat(applied.status()).isEqualTo("REBUILD_APPLIED");
        assertThat(value(participant, BingoMissionType.LONG_DISTANCE_KILL)).isEqualByComparingTo("1");
        assertThat(value(participant, BingoMissionType.WEAPON_KILLS)).isEqualByComparingTo("2");
        assertThat(value(participant, BingoMissionType.KILLS)).isEqualByComparingTo("50");
        assertThat(row(participant, BingoMissionType.LONG_DISTANCE_KILL).isCompleted()).isFalse();
        assertThat(row(participant, BingoMissionType.LONG_DISTANCE_KILL).getCompletedAt()).isNull();
        assertThat(row(participant, BingoMissionType.KILLS).getUpdatedAt()).isEqualTo(unchangedKillsUpdatedAt);
        assertThat(processed.findMatchIds(event.getId(), participant.getId()))
                .containsExactlyInAnyOrder("M1", "M2", "M3");

        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M1", "M2", "M3", "M4"))));
        when(pubgMatches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("M4", match("M4", 4)));
        when(factService.facts(argThat(value -> value.matchId().equals("M4")), anySet()))
                .thenReturn(Map.of("account-a", oneKillFact("M4", match("M4", 4).playedAt())));

        assertThat(aggregation.aggregate(owner.getId(), community.getId(), event.getId()).processedMatches()).isEqualTo(1);
        assertThat(value(participant, BingoMissionType.KILLS)).isEqualByComparingTo("51");
    }

    @Test
    void missingProcessedMatchFailsWithoutChangingProgress() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        setOldProgress(participant, BingoMissionType.LONG_DISTANCE_KILL, 5, true);
        saveProcessed(event, participant, "M1", 1); saveProcessed(event, participant, "M2", 2);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M2", "M3"))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String id = ((List<String>) invocation.getArgument(1)).getFirst();
            return "M2".equals(id) ? Map.of() : Map.of(id, match(id, id.equals("M1") ? 1 : 3));
        });

        TemporaryBingoRebuildResponse result = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(result.status()).isEqualTo("REBUILD_FAILED");
        assertThat(result.failures()).anySatisfy(failure -> {
            assertThat(failure.matchId()).isEqualTo("M2");
            assertThat(failure.reason()).contains("Match 원본");
        });
        assertThat(value(participant, BingoMissionType.LONG_DISTANCE_KILL)).isEqualByComparingTo("5");
    }

    @Test
    void telemetryFailureFailsWithoutWritingZero() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        setOldProgress(participant, BingoMissionType.WEAPON_KILLS, 2, false);
        saveProcessed(event, participant, "M1", 1);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M1"))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenReturn(Map.of("M1", match("M1", 1)));
        when(factService.factsRequired(any(), anySet())).thenThrow(new IllegalStateException("telemetry unavailable"));

        TemporaryBingoRebuildResponse result = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(result.status()).isEqualTo("REBUILD_FAILED");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.matchId()).isEqualTo("M1");
            assertThat(failure.reason()).contains("telemetry unavailable");
        });
        assertThat(value(participant, BingoMissionType.WEAPON_KILLS)).isEqualByComparingTo("2");
    }

    @Test
    void rebuildUsesMatchStartInclusiveEndMinuteAndNextMinuteExclusive() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        saveProcessed(event, participant, "start", 0);
        processed.save(new BingoProcessedMatch(event, participant, "end-minute",
                event.getEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(59), clock.instant()));
        Map<String, PubgMatch> rows = Map.of(
                "before", matchAt("before", event.getStartsAt().minusSeconds(1)),
                "start", matchAt("start", event.getStartsAt()),
                "end-minute", matchAt("end-minute", event.getEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(59)),
                "after", matchAt("after", event.getMatchStartUpperBoundExclusive()));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", new ArrayList<>(rows.keySet()))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String id = ((List<String>) invocation.getArgument(1)).getFirst(); return Map.of(id, rows.get(id));
        });
        when(factService.factsRequired(any(), anySet())).thenAnswer(invocation -> {
            PubgMatch match = invocation.getArgument(0); return Map.of("account-a", facts(match.matchId(), match.playedAt()));
        });

        TemporaryBingoRebuildResponse result = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(result.status()).isEqualTo("REBUILD_PREVIEW_READY");
        assertThat(result.matchCount()).isEqualTo(2);
        assertChange(result.participants().getFirst(), BingoMissionType.MATCHES_PLAYED, "0", "2", true);
        assertThat(value(participant, BingoMissionType.MATCHES_PLAYED)).isZero();
    }

    @Test
    void exactlyTwoDifferentMissionsUpdateAndAllOtherRowsStayUntouched() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        setOldProgress(participant, BingoMissionType.LONG_DISTANCE_KILL, 5, true);
        saveProcessed(event, participant, "M1", 1);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M1"))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList()))
                .thenReturn(Map.of("M1", match("M1", 1)));
        when(factService.factsRequired(any(), anySet())).thenReturn(Map.of(
                "account-a", repairOnlyFacts("M1", match("M1", 1).playedAt())));
        Map<Long, Instant> updatedBefore = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())
                .stream().collect(Collectors.toMap(BingoProgress::getId, BingoProgress::getUpdatedAt));

        TemporaryBingoRebuildResponse preview = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(preview.status()).isEqualTo("REBUILD_PREVIEW_READY");
        assertThat(preview.changedProgressCount()).isEqualTo(2);
        assertThat(preview.participants()).singleElement().satisfies(result ->
                assertThat(result.changes()).extracting(TemporaryBingoRebuildResponse.ProgressChange::missionType)
                        .containsExactlyInAnyOrder(BingoMissionType.LONG_DISTANCE_KILL.name(),
                                BingoMissionType.WEAPON_KILLS.name()));
        clock.set(clock.instant().plusSeconds(1));
        rebuild.apply(owner.getId(), community.getId(), game.getId(), preview.previewToken());

        for (BingoProgress row : progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())) {
            if (Set.of(BingoMissionType.LONG_DISTANCE_KILL, BingoMissionType.WEAPON_KILLS)
                    .contains(row.getCell().getMissionType())) {
                assertThat(row.getUpdatedAt()).isAfter(updatedBefore.get(row.getId()));
            } else {
                assertThat(row.getUpdatedAt()).isEqualTo(updatedBefore.get(row.getId()));
            }
        }
        assertThat(processed.findMatchIds(event.getId(), participant.getId())).containsExactly("M1");
    }

    @Test
    void unprocessedRecentMatchFailsInsteadOfChangingProcessedLedgerOrDoubleCountingLater() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        saveProcessed(event, participant, "M1", 1);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("account-a", "Apple", List.of("M1", "M2"))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String id = ((List<String>) invocation.getArgument(1)).getFirst();
            return Map.of(id, match(id, id.equals("M1") ? 1 : 2));
        });

        TemporaryBingoRebuildResponse result = rebuild.preview(owner.getId(), community.getId(), game.getId());

        assertThat(result.status()).isEqualTo("REBUILD_FAILED");
        assertThat(result.failures()).anySatisfy(failure -> {
            assertThat(failure.matchId()).isEqualTo("M2");
            assertThat(failure.reason()).contains("일반 집계");
        });
        assertThat(processed.findMatchIds(event.getId(), participant.getId())).containsExactly("M1");
    }

    @Test
    void applyRevalidatesPlayerMatchesAndKeepsProgressWhenANewMatchAppears() {
        BingoEvent event = createEvent(); BingoParticipant participant = participant(event);
        setOldProgress(participant, BingoMissionType.LONG_DISTANCE_KILL, 5, true);
        saveProcessed(event, participant, "M1", 1);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(
                List.of(new PubgPlayer("account-a", "Apple", List.of("M1"))),
                List.of(new PubgPlayer("account-a", "Apple", List.of("M1", "M2"))));
        when(pubgMatches.findUniqueMatchesFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String id = ((List<String>) invocation.getArgument(1)).getFirst();
            return Map.of(id, match(id, id.equals("M1") ? 1 : 2));
        });
        when(factService.factsRequired(any(), anySet())).thenAnswer(invocation -> {
            PubgMatch match = invocation.getArgument(0);
            return Map.of("account-a", repairOnlyFacts(match.matchId(), match.playedAt()));
        });
        TemporaryBingoRebuildResponse preview = rebuild.preview(owner.getId(), community.getId(), game.getId());

        TemporaryBingoRebuildResponse applied = rebuild.apply(
                owner.getId(), community.getId(), game.getId(), preview.previewToken());

        assertThat(applied.status()).isEqualTo("REBUILD_FAILED");
        assertThat(applied.failures()).anyMatch(failure -> "M2".equals(failure.matchId()));
        assertThat(value(participant, BingoMissionType.LONG_DISTANCE_KILL)).isEqualByComparingTo("5");
        assertThat(processed.findMatchIds(event.getId(), participant.getId())).containsExactly("M1");
    }

    @Test
    void rejectsConcurrentRebuildForTheSameActiveEvent() throws Exception {
        createEvent();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TemporaryBingoRebuildResponse> first = executor.submit(() ->
                    rebuild.preview(owner.getId(), community.getId(), game.getId()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> rebuild.preview(owner.getId(), community.getId(), game.getId()))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            exception -> assertThat(exception.getReason()).isEqualTo("REBUILD_IN_PROGRESS"));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("REBUILD_FAILED");
        } finally {
            release.countDown(); executor.shutdownNow();
        }
    }

    @Test
    void ordinaryMemberCannotCallTemporaryRebuildApiService() {
        User ordinary = users.save(new User("member-" + IDS.incrementAndGet()));
        CommunityUser membership = new CommunityUser(community, ordinary, CommunityUserRole.MEMBER);
        ReflectionTestUtils.setField(membership, "joinedAt", clock.instant());
        memberships.save(membership);

        assertThatThrownBy(() -> rebuild.preview(ordinary.getId(), community.getId(), game.getId()))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(pubgPlayers, pubgMatches, factService);
    }

    private BingoEvent createEvent() {
        var response = eventService.create(owner.getId(), community.getId(), game.getId(), request());
        return events.findWithCellsById(response.id()).orElseThrow();
    }
    private BingoParticipant participant(BingoEvent event) {
        return participants.findByEventIdOrderByIdAsc(event.getId()).getFirst();
    }
    private void setOldProgress(BingoParticipant participant, BingoMissionType type, int value, boolean completed) {
        BingoProgress row = row(participant, type);
        row.replaceSnapshot(BigDecimal.valueOf(value), 0, completed,
                completed ? clock.instant().minusSeconds(10) : null, completed ? "OLD" : null,
                completed ? clock.instant().minusSeconds(10) : null, clock.instant());
        progress.save(row);
    }
    private BigDecimal value(BingoParticipant participant, BingoMissionType type) {
        return row(participant, type).getCurrentValue();
    }
    private BingoProgress row(BingoParticipant participant, BingoMissionType type) {
        return progress.findByParticipantIdOrderByCellPositionAsc(participant.getId()).stream()
                .filter(value -> value.getCell().getMissionType() == type).findFirst().orElseThrow();
    }
    private void saveProcessed(BingoEvent event, BingoParticipant participant, String matchId, int minute) {
        processed.save(new BingoProcessedMatch(event, participant, matchId,
                event.getStartsAt().plus(Duration.ofMinutes(minute)), clock.instant()));
    }
    private void completeWinningKillCompetition() {
        KillCompetition competition = new KillCompetition(community, game, member, "킬내기",
                KillCompetitionGameMode.SOLO, clock.instant().minus(Duration.ofMinutes(10)), clock.instant().minus(Duration.ofHours(2)));
        KillCompetitionParticipant player = new KillCompetitionParticipant(competition, member, "account-a", "Apple");
        competition.addParticipant(player); competition.closeRecruitment(clock.instant().minus(Duration.ofHours(1)));
        competition.start(clock.instant().minus(Duration.ofHours(1)));
        player.recordFinal(3, 1); competition.complete(clock.instant()); competitions.save(competition);
    }
    private BingoEventRequest request() {
        List<BingoMissionType> types = List.of(BingoMissionType.KILLS, BingoMissionType.DAMAGE_DEALT,
                BingoMissionType.ASSISTS, BingoMissionType.MATCHES_PLAYED, BingoMissionType.DBNOS,
                BingoMissionType.TOP10, BingoMissionType.HEADSHOT_KILLS, BingoMissionType.REVIVES,
                BingoMissionType.WALK_DISTANCE, BingoMissionType.RIDE_DISTANCE, BingoMissionType.HEALS,
                BingoMissionType.BOOSTS, BingoMissionType.LONG_DISTANCE_KILL, BingoMissionType.WEAPON_KILLS,
                BingoMissionType.WINS, BingoMissionType.KILL_BET_WIN);
        List<BingoEventRequest.Cell> cells = new ArrayList<>();
        for (int index = 0; index < types.size(); index++) {
            BingoMissionType type = types.get(index); Map<String,Object> options = new LinkedHashMap<>();
            if (type == BingoMissionType.LONG_DISTANCE_KILL) options.put("distance", 200);
            if (type == BingoMissionType.WEAPON_KILLS) options.put("weapon", "VSS");
            BigDecimal target = switch (type) {
                case KILLS -> BigDecimal.valueOf(50);
                case LONG_DISTANCE_KILL, WEAPON_KILLS -> BigDecimal.valueOf(5);
                default -> BigDecimal.ONE;
            };
            cells.add(new BingoEventRequest.Cell(index, type, BingoAggregationType.EVENT_TOTAL,
                    BingoOperator.GREATER_THAN_OR_EQUAL, target, null, options, null));
        }
        return new BingoEventRequest("복구 빙고", null, clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
                4, 1, true, false, BingoStatus.ACTIVE, cells);
    }
    private PubgMatch match(String id, int minute) {
        return matchAt(id, clock.instant().minus(Duration.ofMinutes(30)).plus(Duration.ofMinutes(minute)));
    }
    private PubgMatch matchAt(String id, Instant at) {
        return new PubgMatch(id, at, "squad-fpp", "Erangel_Main", "official", false,
                "https://telemetry-cdn.pubg.com/" + id, List.of(new PubgTeam(List.of(
                new PubgParticipant("account-a", "Apple", 0)))));
    }
    private PlayerMatchFacts facts(String id, Instant at) {
        int kills = switch (id) { case "M1" -> 20; case "M2", "M3" -> 15; default -> 1; };
        List<PlayerMatchFacts.KillFact> killFacts = switch (id) {
            case "M1" -> List.of(new PlayerMatchFacts.KillFact("VSS", "DMR", null, 250, false, at));
            case "M2" -> List.of(new PlayerMatchFacts.KillFact("VSS", "DMR", null, 100, false, at));
            default -> List.of();
        };
        Map<String,BigDecimal> metrics = new HashMap<>();
        metrics.put("KILLS", BigDecimal.valueOf(kills)); metrics.put("DAMAGE_DEALT", BigDecimal.valueOf(100));
        metrics.put("ASSISTS", BigDecimal.ONE); metrics.put("MATCHES_PLAYED", BigDecimal.ONE);
        metrics.put("DBNOS", BigDecimal.ONE); metrics.put("TOP10", BigDecimal.ONE);
        metrics.put("HEADSHOT_KILLS", BigDecimal.ONE); metrics.put("REVIVES", BigDecimal.ONE);
        metrics.put("WALK_DISTANCE", BigDecimal.valueOf(1000)); metrics.put("RIDE_DISTANCE", BigDecimal.valueOf(2000));
        metrics.put("HEALS", BigDecimal.ONE); metrics.put("BOOSTS", BigDecimal.ONE); metrics.put("WINS", BigDecimal.ONE);
        return new PlayerMatchFacts(id, at, "Erangel_Main", "squad-fpp", metrics, killFacts, Map.of(), 0, at);
    }
    private PlayerMatchFacts oneKillFact(String id, Instant at) {
        return new PlayerMatchFacts(id, at, "Erangel_Main", "squad-fpp",
                Map.of("KILLS", BigDecimal.ONE, "MATCHES_PLAYED", BigDecimal.ONE), List.of(), Map.of(), 0, at);
    }
    private PlayerMatchFacts repairOnlyFacts(String id, Instant at) {
        return new PlayerMatchFacts(id, at, "Erangel_Main", "squad-fpp", Map.of(), List.of(
                new PlayerMatchFacts.KillFact("VSS", "DMR", null, 250, false, at),
                new PlayerMatchFacts.KillFact("VSS", "DMR", null, 100, false, at)), Map.of(), 0, at);
    }
    private void assertChange(TemporaryBingoRebuildResponse.ParticipantResult result, BingoMissionType type,
                              String previous, String recomputed, boolean completed) {
        assertThat(result.changes()).filteredOn(change -> change.missionType().equals(type.name())).singleElement()
                .satisfies(change -> {
                    assertThat(change.previousValue()).isEqualByComparingTo(previous);
                    assertThat(change.recomputedValue()).isEqualByComparingTo(recomputed);
                    assertThat(change.recomputedCompleted()).isEqualTo(completed);
                });
    }

    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary MutableClock rebuildClock(){ return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private Instant instant=Instant.EPOCH; void set(Instant value){instant=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
    }
}
