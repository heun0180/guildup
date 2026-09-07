package com.guildup.community;

import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
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
    void explicitSyncPersistsSummariesMatchesAndEveryTeammate() throws Exception {
        Fixture fixture = createFixture();
        stubSuccessfulPubgLookup();

        mvc.perform(post(syncPath(fixture)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sync.status").value("SUCCESS"))
                .andExpect(jsonPath("$.sync.lastSuccessfulSyncAt").value(FIRST_SYNC.toString()))
                .andExpect(jsonPath("$.sync.nextSyncAvailableAt").value(FIRST_SYNC.plusSeconds(86400).toString()))
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
        verify(playerService).findByNames(eq("kakao"), eq(List.of("sa-gwa", "jul-mi")));
        verify(matchService).findUniqueMatches(eq("kakao"), eq(List.of("match-1")));
    }

    @Test
    void successCooldownIsSharedByEveryOperatorAndExpiresAfterExactly24Hours() throws Exception {
        Fixture fixture = createFixture();
        memberships.save(new CommunityUser(fixture.community(), admin, CommunityUserRole.ADMIN));
        stubSuccessfulPubgLookup();

        mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isOk());
        mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isTooManyRequests());
        mvc.perform(post(syncPath(fixture)).session(adminSession)).andExpect(status().isTooManyRequests());
        verify(matchService, times(1)).findUniqueMatches(eq("kakao"), anyList());

        now.set(FIRST_SYNC.plusSeconds(86400));
        when(playerService.findByAccountIds(eq("kakao"), anyList())).thenReturn(List.of(
                applePlayer(), julmiPlayer()
        ));
        mvc.perform(post(syncPath(fixture)).session(adminSession)).andExpect(status().isOk());
        verify(matchService, times(2)).findUniqueMatches(eq("kakao"), anyList());
    }

    @Test
    void failedRefreshKeepsPreviousDataAndDoesNotRestart24HourCooldown() throws Exception {
        Fixture fixture = createFixture();
        stubSuccessfulPubgLookup();
        mvc.perform(post(syncPath(fixture)).session(ownerSession)).andExpect(status().isOk());

        now.set(FIRST_SYNC.plusSeconds(86400));
        when(playerService.findByAccountIds(eq("kakao"), anyList()))
                .thenThrow(new PubgApiException("PUBG 장애"));
        mvc.perform(post(syncPath(fixture)).session(ownerSession))
                .andExpect(status().isServiceUnavailable());

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
    void simultaneousSyncRequestsExecutePubgLookupOnlyOnce() throws Exception {
        Fixture fixture = createFixture();
        memberships.save(new CommunityUser(fixture.community(), admin, CommunityUserRole.ADMIN));
        CountDownLatch enteredPubg = new CountDownLatch(1);
        CountDownLatch releasePubg = new CountDownLatch(1);
        when(playerService.findByNames(eq("kakao"), anyList())).thenAnswer(ignored -> {
            enteredPubg.countDown();
            if (!releasePubg.await(5, TimeUnit.SECONDS)) throw new AssertionError("sync did not resume");
            return List.of(applePlayer(), julmiPlayer());
        });
        when(playerService.findByAccountIds(eq("kakao"), anyList())).thenReturn(List.of());
        when(matchService.findUniqueMatches(eq("kakao"), anyList())).thenReturn(matchMap());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> mvc.perform(post(syncPath(fixture)).session(ownerSession))
                    .andReturn().getResponse().getStatus());
            assertThat(enteredPubg.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(post(syncPath(fixture)).session(adminSession))
                    .andExpect(status().isConflict());
            releasePubg.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releasePubg.countDown();
        }
        verify(playerService, times(1)).findByNames(eq("kakao"), anyList());
        verify(matchService, times(1)).findUniqueMatches(eq("kakao"), anyList());
    }

    private Fixture createFixture() {
        Community community = communityService.createCommunity("치즈 클랜", owner.getId());
        CommunityGame game = games.findFirstByCommunityIdOrderByIdAsc(community.getId()).orElseThrow();
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
        when(playerService.findByNames(eq("kakao"), anyList()))
                .thenReturn(List.of(applePlayer(), julmiPlayer()));
        when(playerService.findByAccountIds(eq("kakao"), anyList())).thenReturn(List.of());
        when(matchService.findUniqueMatches(eq("kakao"), anyList())).thenReturn(matchMap());
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
        return "/api/communities/" + fixture.community().getId() + "/member-activities";
    }

    private String syncPath(Fixture fixture) {
        return listPath(fixture) + "/sync";
    }

    private String detailPath(Fixture fixture, CommunityMember member) {
        return "/api/communities/" + fixture.community().getId()
                + "/members/" + member.getId() + "/activity";
    }

    private record Fixture(
            Community community,
            CommunityGame game,
            CommunityMember apple,
            CommunityMember julmi
    ) {}
}
