package com.guildup.community;

import com.guildup.pubg.model.PubgPlatform;

import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.domain.MonitoringSeverity;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgTeam;
import com.guildup.pubg.service.PubgMatchService;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:activity-sync-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@com.guildup.support.SessionCsrfTestClient.WithSessionCsrf
class CommunityMemberActivitySyncFlowTests {

    private static final Instant FIRST_SYNC = Instant.parse("2026-09-07T15:30:00Z");
    private static final Instant MATCH_TIME = Instant.parse("2026-09-07T14:14:00Z");

    @Autowired MockMvc mvc;
    @Autowired CommunityService communityService;
    @Autowired UserRepository users;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityGameActivityRuleRepository rules;
    @Autowired CommunityGameActivitySyncRepository syncs;
    @Autowired CommunityGameNicknameRuleRepository nicknameRules;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository accounts;
    @Autowired CommunityMemberActivitySnapshotRepository snapshots;
    @Autowired MonitoringEventRepository monitoringEvents;
    @Autowired @Qualifier("communityActivitySyncExecutor") ThreadPoolTaskExecutor activityExecutor;

    @MockitoBean Clock clock;
    @MockitoBean PubgPlayerService playerService;
    @MockitoBean PubgMatchService matchService;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private User owner;
    private User admin;
    private MockHttpSession ownerSession;
    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        monitoringEvents.deleteAll();
        snapshots.deleteAll();
        syncs.deleteAll();
        nicknameRules.deleteAll();
        accounts.deleteAll();
        members.deleteAll();
        memberships.deleteAll();
        rules.deleteAll();
        games.deleteAll();
        communities.deleteAll();
        users.deleteAll();

        now.set(FIRST_SYNC);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        owner = users.save(new User("운영진 A"));
        admin = users.save(new User("운영진 B"));
        ownerSession = session(owner);
        adminSession = session(admin);
    }

    @AfterEach
    void waitForBackgroundTasks() {
        await().atMost(Duration.ofSeconds(10)).until(() -> activityExecutor.getActiveCount() == 0);
    }

    @Test
    void listAndDetailReadsNeverCallPubgApi() throws Exception {
        Fixture fixture = createFixture();

        mvc.perform(get(listPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sync.status").value("NEVER_SYNCED"))
                .andExpect(jsonPath("$.sync.syncAvailable").value(true))
                .andExpect(jsonPath("$.members[0].status").value("ACCOUNT_VERIFICATION_REQUIRED"));
        mvc.perform(get(detailPath(fixture, fixture.apple())).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").isEmpty());

        verifyNoInteractions(playerService, matchService);
    }

    @Test
    void steamActivityUsesSteamAccountsAndPreservesKakaoAccounts() throws Exception {
        Fixture kakao = createFixture();
        CommunityGame steam = games.save(new CommunityGame(kakao.community(), GameType.BATTLEGROUNDS_STEAM));
        rules.save(CommunityGameActivityRule.defaultRule(steam));
        accounts.save(new CommunityMemberAccount(kakao.apple(), PubgPlatform.KAKAO, "account.kakao", "KakaoNick"));
        accounts.save(new CommunityMemberAccount(kakao.apple(), PubgPlatform.STEAM, "account.steam", "SteamNick"));
        accounts.save(new CommunityMemberAccount(kakao.julmi(), PubgPlatform.STEAM, "account.steam2", "SteamNick2"));
        nicknameRules.save(new CommunityGameNicknameRule(kakao.community(), GameType.BATTLEGROUNDS_STEAM,
                GameNicknameRuleStrategyType.FULL_NICKNAME, null, null, false, null, "sa-gwa", "sa-gwa"));
        var fixture = new Fixture(kakao.community(), steam, kakao.apple(), kakao.julmi());
        when(playerService.findByAccountIdsFresh(eq("steam"), anyList())).thenReturn(List.of(
                new PubgPlayer("account.steam", "SteamNick", List.of()),
                new PubgPlayer("account.steam2", "SteamNick2", List.of())));
        when(matchService.findUniqueMatchesFresh(eq("steam"), anyList(), anyLong(), anyLong())).thenReturn(Map.of());
        syncSuccessfully(fixture, ownerSession);
        verify(playerService).findByAccountIdsFresh(eq("steam"), org.mockito.ArgumentMatchers.argThat(ids ->
                ids.size() == 2 && ids.containsAll(List.of("account.steam", "account.steam2"))));
        verify(playerService, never()).findByAccountIdsFresh(eq("kakao"), anyList());
        assertThat(accounts.findByCommunityMemberIdAndProviderAndPlatform(kakao.apple().getId(),
                com.guildup.account.domain.ExternalAccountProvider.PUBG, PubgPlatform.KAKAO).orElseThrow().getExternalUserId())
                .isEqualTo("account.kakao");
        mvc.perform(get(listPath(fixture)).session(ownerSession)).andExpect(status().isOk());
    }

    @Test
    void explicitSyncPersistsSummariesMatchesAndEveryTeammate() throws Exception {
        Fixture fixture = createFixture();
        stubSuccessfulPubgLookup();

        mvc.perform(post(syncPath(fixture)).session(ownerSession))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sync.status").value("SYNCING"));
        awaitSync(fixture, CommunityGameActivitySyncStatus.SUCCESS);
        mvc.perform(get(listPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sync.status").value("SUCCESS"))
                .andExpect(jsonPath("$.sync.lastSuccessfulSyncAt").value(FIRST_SYNC.toString()))
                .andExpect(jsonPath("$.sync.nextSyncAvailableAt").value(FIRST_SYNC.plusSeconds(10800).toString()))
                .andExpect(jsonPath("$.sync.syncAvailable").value(false))
                .andExpect(jsonPath("$.activeMembers").value(2));

        mvc.perform(get(detailPath(fixture, fixture.apple())).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].matchId").value("match-1"))
                .andExpect(jsonPath("$.matches[0].activityRecognized").value(true))
                .andExpect(jsonPath("$.matches[0].clanMemberCountInTeam").value(2))
                .andExpect(jsonPath("$.matches[0].playersInTeam.length()").value(3))
                .andExpect(jsonPath("$.matches[0].playersInTeam[2].pubgNickname").value("friend123"))
                .andExpect(jsonPath("$.matches[0].playersInTeam[2].clanMember").value(false))
                .andExpect(jsonPath("$.matches[0].playersInTeam[2].communityMemberId").doesNotExist());

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(members.count()).isEqualTo(2);
        assertThat(snapshots.count()).isEqualTo(2);
        verify(playerService).findByNamesFresh(eq("kakao"), eq(List.of("sa-gwa", "jul-mi")));
        verify(matchService).findUniqueMatchesFresh(
                eq("kakao"), eq(List.of("match-1")),
                eq(fixture.community().getId()), eq(fixture.game().getId())
        );
    }

    @Test
    void successCooldownIsSharedByEveryOperatorAndExpiresAfterExactly3Hours() throws Exception {
        Fixture fixture = createFixture();
        memberships.save(new CommunityUser(fixture.community(), admin, CommunityUserRole.ADMIN));
        stubSuccessfulPubgLookup();

        syncSuccessfully(fixture, ownerSession);
        mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isTooManyRequests());
        mvc.perform(post(syncPath(fixture)).session(adminSession)).andExpect(status().isTooManyRequests());
        assertThat(monitoringEvents.count()).isZero();
        verify(matchService, times(1)).findUniqueMatchesFresh(
                eq("kakao"), anyList(), anyLong(), anyLong()
        );

        now.set(FIRST_SYNC.plusSeconds(10800));
        when(playerService.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                applePlayer(), julmiPlayer()
        ));
        syncSuccessfully(fixture, adminSession);
        verify(matchService, times(2)).findUniqueMatchesFresh(
                eq("kakao"), anyList(), anyLong(), anyLong()
        );
    }

    @Test
    void failedRefreshKeepsPreviousDataAndDoesNotRestart3HourCooldown() throws Exception {
        Fixture fixture = createFixture();
        stubSuccessfulPubgLookup();
        syncSuccessfully(fixture, ownerSession);

        now.set(FIRST_SYNC.plusSeconds(10800));
        when(playerService.findByAccountIdsFresh(eq("kakao"), anyList()))
                .thenThrow(new PubgApiException("PUBG 장애"));
        mvc.perform(post(syncPath(fixture)).session(ownerSession))
                .andExpect(status().isAccepted());
        awaitSync(fixture, CommunityGameActivitySyncStatus.FAILED);
        mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isTooManyRequests());

        var sync = syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow();
        assertThat(sync.getSyncStatus()).isEqualTo(CommunityGameActivitySyncStatus.FAILED);
        assertThat(sync.getLastSuccessfulSyncAt()).isEqualTo(FIRST_SYNC);
        assertThat(snapshots.count()).isEqualTo(2);
        mvc.perform(get(detailPath(fixture, fixture.apple())).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].matchId").value("match-1"));

        now.set(now.get().plusSeconds(300));
        mvc.perform(get(listPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sync.status").value("FAILED"))
                .andExpect(jsonPath("$.sync.syncAvailable").value(true));
    }

    @Test
    void exhaustedMatchFailureMarksSyncAsFailed() throws Exception {
        Fixture fixture = createFixture();
        when(playerService.findByNamesFresh(eq("kakao"), anyList()))
                .thenReturn(List.of(applePlayer(), julmiPlayer()));
        when(playerService.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of());
        when(matchService.findUniqueMatchesFresh(
                eq("kakao"), anyList(), eq(fixture.community().getId()), eq(fixture.game().getId())
        )).thenThrow(new PubgApiException("PUBG match retries exhausted", null, 503, true));

        mvc.perform(post(syncPath(fixture)).session(ownerSession))
                .andExpect(status().isAccepted());
        awaitSync(fixture, CommunityGameActivitySyncStatus.FAILED);

        var sync = syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow();
        assertThat(sync.getSyncStatus()).isEqualTo(CommunityGameActivitySyncStatus.FAILED);
        assertThat(snapshots.count()).isZero();
        var failure = monitoringEvents.findAll().stream()
                .filter(event -> event.getEventCode() == MonitoringEventCode.COMMUNITY_ACTIVITY_SYNC_FAILED)
                .findFirst().orElseThrow();
        assertThat(failure.getSeverity()).isEqualTo(MonitoringSeverity.ERROR);
        assertThat(failure.getCommunityId()).isEqualTo(fixture.community().getId());
        assertThat(failure.getUserId()).isEqualTo(owner.getId());
        assertThat(failure.getMetadata()).containsEntry("status", 503)
                .containsEntry("upstreamStatus", 503)
                .containsEntry("pubgErrorCode", "PUBG_UNAVAILABLE")
                .containsKey("elapsedMs");
        assertThat(failure.getMetadata().toString()).doesNotContain("PUBG match retries exhausted");
        // PUBG 실패는 접수 응답 이후의 작업 실패이므로 HTTP 5xx로 기록하지 않는다.
        assertThat(monitoringEvents.findAll()).noneMatch(event -> event.getEventCode() == MonitoringEventCode.HTTP_5XX);
    }

    @Test
    void activityViewUsesCurrentNicknameAndRejectsSnapshotFromPreviousPubgAccount() throws Exception {
        Fixture fixture = createFixture();
        stubSuccessfulPubgLookup();
        syncSuccessfully(fixture, ownerSession);

        CommunityMemberAccount previous = accounts.findByCommunityMemberIdAndProviderAndPlatform(
                fixture.apple().getId(), com.guildup.account.domain.ExternalAccountProvider.PUBG, PubgPlatform.KAKAO
        ).orElseThrow();
        previous.updateExternalUsername("sa-gwa-renamed");
        accounts.saveAndFlush(previous);
        mvc.perform(get(listPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].gameNickname").value("sa-gwa-renamed"));

        accounts.delete(previous);
        accounts.flush();
        accounts.saveAndFlush(new CommunityMemberAccount(
                fixture.apple(),
                PubgPlatform.KAKAO,
                "account.apple.new",
                "sa-gwa-new"
        ));

        mvc.perform(get(listPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].pubgAccountId").value("account.apple.new"))
                .andExpect(jsonPath("$.members[0].gameNickname").value("sa-gwa-new"))
                .andExpect(jsonPath("$.members[0].status").value("ACCOUNT_VERIFICATION_REQUIRED"));
        mvc.perform(get(detailPath(fixture, fixture.apple())).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.gameNickname").value("sa-gwa-new"))
                .andExpect(jsonPath("$.member.status").value("ACCOUNT_VERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.matches").isEmpty());
    }

    @Test
    void simultaneousSyncRequestsExecutePubgLookupOnlyOnce() throws Exception {
        Fixture fixture = createFixture();
        memberships.save(new CommunityUser(fixture.community(), admin, CommunityUserRole.ADMIN));
        CountDownLatch enteredPubg = new CountDownLatch(1);
        CountDownLatch releasePubg = new CountDownLatch(1);
        when(playerService.findByNamesFresh(eq("kakao"), anyList())).thenAnswer(ignored -> {
            enteredPubg.countDown();
            if (!releasePubg.await(10, TimeUnit.SECONDS)) throw new AssertionError("sync did not resume");
            assertThat(Thread.currentThread().getName()).startsWith("community-activity-sync-");
            assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isNull();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("activity-request-123");
            return List.of(applePlayer(), julmiPlayer());
        });
        when(playerService.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of());
        when(matchService.findUniqueMatchesFresh(
                eq("kakao"), anyList(), anyLong(), anyLong()
        )).thenReturn(matchMap());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> mvc.perform(post(syncPath(fixture)).session(ownerSession)
                            .header("X-Request-ID", "activity-request-123"))
                    .andReturn().getResponse().getStatus());
            assertThat(enteredPubg.await(5, TimeUnit.SECONDS)).isTrue();
            // PUBG 조회를 막아 둔 상태에서도 HTTP는 이미 끝나야 한다.
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow().getSyncStatus())
                    .isEqualTo(CommunityGameActivitySyncStatus.SYNCING);
            assertThat(snapshots.count()).isZero();
            ownerSession.invalidate(); // 접수 후 브라우저/세션 종료가 worker를 중단하지 않는다.
            mvc.perform(get(listPath(fixture)).session(adminSession))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.sync.status").value("SYNCING"));
            mvc.perform(post(syncPath(fixture)).session(adminSession))
                    .andExpect(status().isConflict());
            releasePubg.countDown();
            awaitSync(fixture, CommunityGameActivitySyncStatus.SUCCESS);
        } finally {
            releasePubg.countDown();
        }
        verify(playerService, times(1)).findByNamesFresh(eq("kakao"), anyList());
        verify(matchService, times(1)).findUniqueMatchesFresh(
                eq("kakao"), anyList(), anyLong(), anyLong()
        );
    }

    @Test
    void nanosecondClockUsesDatabasePrecisionForTheAttempt() throws Exception {
        Fixture fixture = createFixture();
        now.set(FIRST_SYNC.plusNanos(123456789));
        stubSuccessfulPubgLookup();
        syncSuccessfully(fixture, ownerSession);
        assertThat(syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow().getLastSyncAttemptAt())
                .isEqualTo(FIRST_SYNC.plusNanos(123456000));
    }

    @Test
    void supersededWorkerCannotReplaceTheRecoveredResult() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        stubSuccessfulPubgLookup();
        when(playerService.findByNamesFresh(eq("kakao"), anyList())).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("old task not released");
            }
            return List.of(applePlayer(), julmiPlayer());
        });
        try {
            mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isAccepted());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            now.set(FIRST_SYNC.plusSeconds(1800));
            mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isAccepted());
            await().atMost(Duration.ofSeconds(5)).until(() -> syncs.findByCommunityGameId(fixture.game().getId())
                    .orElseThrow().getSyncStatus() == CommunityGameActivitySyncStatus.SUCCESS);
            release.countDown();
            awaitSync(fixture, CommunityGameActivitySyncStatus.SUCCESS);
            assertThat(snapshots.count()).isEqualTo(2);
            assertThat(syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow().getLastSuccessfulSyncAt())
                    .isEqualTo(now.get());
            assertThat(monitoringEvents.count()).isZero();
        } finally { release.countDown(); }
    }

    @Test
    void supersededFailureCannotFailTheNewAttempt() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch oldEntered = new CountDownLatch(1), newEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        stubSuccessfulPubgLookup();
        when(playerService.findByNamesFresh(eq("kakao"), anyList())).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) {
                oldEntered.countDown();
                if (!releaseOld.await(10, TimeUnit.SECONDS)) throw new AssertionError("old task not released");
                throw new PubgApiException("old attempt failed");
            }
            newEntered.countDown();
            if (!releaseNew.await(10, TimeUnit.SECONDS)) throw new AssertionError("new task not released");
            return List.of(applePlayer(), julmiPlayer());
        });
        try {
            mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isAccepted());
            assertThat(oldEntered.await(5, TimeUnit.SECONDS)).isTrue();
            now.set(FIRST_SYNC.plusSeconds(1800));
            mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isAccepted());
            assertThat(newEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseOld.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> activityExecutor.getActiveCount() == 1);
            assertThat(syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow().getSyncStatus())
                    .isEqualTo(CommunityGameActivitySyncStatus.SYNCING);
            releaseNew.countDown();
            awaitSync(fixture, CommunityGameActivitySyncStatus.SUCCESS);
        } finally { releaseOld.countDown(); releaseNew.countDown(); }
    }

    private void syncSuccessfully(Fixture fixture, MockHttpSession session) throws Exception {
        mvc.perform(post(syncPath(fixture)).session(session)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sync.status").value("SYNCING"));
        awaitSync(fixture, CommunityGameActivitySyncStatus.SUCCESS);
    }

    private void awaitSync(Fixture fixture, CommunityGameActivitySyncStatus status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(syncs.findByCommunityGameId(fixture.game().getId()).orElseThrow().getSyncStatus()).isEqualTo(status);
            assertThat(activityExecutor.getActiveCount()).isZero();
        });
    }

    private Fixture createFixture() {
        Community community = communityService.createCommunity("치즈 클랜", owner.getId());
        CommunityGame game = games.findByCommunityIdOrderByIdAsc(community.getId()).get(0);
        memberships.flush();
        CommunityMember apple = members.save(new CommunityMember(community, "sa-gwa"));
        CommunityMember julmi = members.save(new CommunityMember(community, "jul-mi"));
        nicknameRules.save(new CommunityGameNicknameRule(
                community, GameType.BATTLEGROUNDS_KAKAO,
                GameNicknameRuleStrategyType.FULL_NICKNAME,
                null, null, false, null, "sa-gwa", "sa-gwa"
        ));
        return new Fixture(community, game, apple, julmi);
    }

    private void stubSuccessfulPubgLookup() {
        when(playerService.findByNamesFresh(eq("kakao"), anyList()))
                .thenReturn(List.of(applePlayer(), julmiPlayer()));
        when(playerService.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of());
        when(matchService.findUniqueMatchesFresh(
                eq("kakao"), anyList(), anyLong(), anyLong()
        )).thenReturn(matchMap());
    }

    private PubgPlayer applePlayer() {
        return new PubgPlayer("account.apple", "sa-gwa", List.of("match-1"));
    }

    private PubgPlayer julmiPlayer() {
        return new PubgPlayer("account.julmi", "jul-mi", List.of("match-1"));
    }

    private Map<String, PubgMatch> matchMap() {
        PubgMatch match = new PubgMatch("match-1", MATCH_TIME, "squad", List.of(
                new PubgTeam(List.of(
                        new PubgParticipant("account.apple", "sa-gwa"),
                        new PubgParticipant("account.julmi", "jul-mi"),
                        new PubgParticipant("account.friend", "friend123")
                ))
        ));
        return Map.of(match.matchId(), match);
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("LOGIN_USER_ID", user.getId());
        return session;
    }

    private String listPath(Fixture fixture) {
        return "/api/communities/" + fixture.community().getId() + "/games/"
                + fixture.game().getId() + "/activities";
    }

    private String syncPath(Fixture fixture) {
        return listPath(fixture) + "/sync";
    }

    private String detailPath(Fixture fixture, CommunityMember member) {
        return listPath(fixture) + "/members/" + member.getId();
    }

    private record Fixture(
            Community community,
            CommunityGame game,
            CommunityMember apple,
            CommunityMember julmi
    ) {}
}
