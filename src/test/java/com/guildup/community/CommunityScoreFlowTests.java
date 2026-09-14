package com.guildup.community;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.dto.AttendanceCheckResponse;
import com.guildup.community.dto.CommunityRankingsResponse;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityAttendanceService;
import com.guildup.community.service.CommunityRankingService;
import com.guildup.community.service.CommunityScoreService;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-score;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Import(CommunityScoreFlowTests.TestClockConfig.class)
class CommunityScoreFlowTests {
    @Autowired MockMvc mvc;
    @Autowired CommunityService communityService;
    @Autowired CommunityAttendanceService attendanceService;
    @Autowired CommunityScoreService scoreService;
    @Autowired CommunityRankingService rankingService;
    @Autowired CommunityAttendanceRepository attendances;
    @Autowired CommunityScoreHistoryRepository histories;
    @Autowired CommunityMemberScoreRepository scores;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityGameRepository communityGames;
    @Autowired CommunityRepository communities;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    User user;
    MockHttpSession session;

    @BeforeEach
    void setUp() {
        histories.deleteAll();
        attendances.deleteAll();
        scores.deleteAll();
        memberAccounts.deleteAll();
        members.deleteAll();
        communityUsers.deleteAll();
        communityGames.deleteAll();
        communities.deleteAll();
        userAccounts.deleteAll();
        users.deleteAll();

        clock.set(Instant.parse("2026-09-14T03:00:00Z"));
        user = users.save(new User("애플"));
        userAccounts.save(new UserExternalAccount(
                user, ExternalAccountProvider.DISCORD, "discord-apple", "apple"
        ));
        session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
    }

    @Test
    void firstAttendanceCreatesRecordHistoryAndSummaryAndDuplicateIsIdempotent() throws Exception {
        Fixture fixture = communityWithMember("치즈 클랜", user, "discord-apple", "애플 서버닉");

        mvc.perform(get(path(fixture.community(), "/attendance/me")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attended").value(false))
                .andExpect(jsonPath("$.attendanceDate").doesNotExist())
                .andExpect(jsonPath("$.currentScore").value(0));

        mvc.perform(post(path(fixture.community(), "/attendance")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attended").value(true))
                .andExpect(jsonPath("$.attendanceDate").value("2026-09-14"))
                .andExpect(jsonPath("$.scoreAdded").value(1))
                .andExpect(jsonPath("$.currentScore").value(1));

        AttendanceCheckResponse duplicate = attendanceService.attend(user.getId(), fixture.community().getId());
        assertThat(duplicate.scoreAdded()).isZero();
        assertThat(duplicate.currentScore()).isEqualTo(1);
        assertThat(attendances.countByCommunityMemberIdAndAttendanceDate(
                fixture.member().getId(), LocalDate.of(2026, 9, 14))).isEqualTo(1);
        assertThat(histories.findByCommunityMemberIdOrderByCreatedAtDescIdDesc(fixture.member().getId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getScoreChange()).isEqualTo(1);
                    assertThat(history.getScoreType()).isEqualTo(CommunityScoreType.ATTENDANCE);
                    assertThat(history.getDescription()).isEqualTo("일일 출석");
                });
        assertThat(scores.findByCommunityMemberId(fixture.member().getId()).orElseThrow().getTotalScore())
                .isEqualTo(1);
    }

    @Test
    void seoulMidnightStartsANewAttendanceDate() {
        Fixture fixture = communityWithMember("치즈 클랜", user, "discord-apple", "애플");
        clock.set(Instant.parse("2026-09-14T14:59:00Z")); // 서울 23:59
        assertThat(attendanceService.attend(user.getId(), fixture.community().getId()).attendanceDate())
                .isEqualTo(LocalDate.of(2026, 9, 14));

        clock.set(Instant.parse("2026-09-14T15:01:00Z")); // 서울 다음 날 00:01
        AttendanceCheckResponse nextDay = attendanceService.attend(user.getId(), fixture.community().getId());
        assertThat(nextDay.attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(nextDay.currentScore()).isEqualTo(2);
        assertThat(attendances.count()).isEqualTo(2);
        assertThat(histories.countByCommunityMemberId(fixture.member().getId())).isEqualTo(2);
    }

    @Test
    void sameUserCanAttendOnceInEachCommunity() {
        Fixture first = communityWithMember("치즈", user, "discord-apple", "애플");
        Fixture second = communityWithMember("사과", user, "discord-apple", "애플");

        assertThat(attendanceService.attend(user.getId(), first.community().getId()).currentScore()).isEqualTo(1);
        assertThat(attendanceService.attend(user.getId(), second.community().getId()).currentScore()).isEqualTo(1);
        assertThat(scores.findByCommunityMemberId(first.member().getId()).orElseThrow().getTotalScore()).isEqualTo(1);
        assertThat(scores.findByCommunityMemberId(second.member().getId()).orElseThrow().getTotalScore()).isEqualTo(1);
    }

    @Test
    void nonMemberCannotAttend() {
        User owner = users.save(new User("주인"));
        Fixture foreign = communityWithMember("비공개", owner, null, "주인");

        assertThatThrownBy(() -> attendanceService.attend(user.getId(), foreign.community().getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void concurrentRequestsAwardExactlyOnce() throws Exception {
        Fixture fixture = communityWithMember("치즈", user, "discord-apple", "애플");
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AttendanceCheckResponse>> futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return attendanceService.attend(user.getId(), fixture.community().getId());
                    })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<AttendanceCheckResponse> responses = futures.stream().map(this::getFuture).toList();

            assertThat(responses).extracting(AttendanceCheckResponse::scoreAdded)
                    .containsExactlyInAnyOrder(1, 0, 0, 0, 0);
            assertThat(attendances.count()).isEqualTo(1);
            assertThat(histories.count()).isEqualTo(1);
            assertThat(scores.findByCommunityMemberId(fixture.member().getId()).orElseThrow().getTotalScore())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseUniqueConstraintRejectsDuplicateAttendance() {
        Fixture fixture = communityWithMember("치즈", user, "discord-apple", "애플");
        LocalDate date = LocalDate.of(2026, 9, 14);
        attendances.saveAndFlush(new CommunityAttendance(fixture.member(), date, clock.instant()));

        assertThatThrownBy(() -> attendances.saveAndFlush(
                new CommunityAttendance(fixture.member(), date, clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attendances.count()).isEqualTo(1);
    }

    @Test
    void attendanceHistoryAndSummaryRollBackTogetherWhenSummaryFails() {
        Fixture fixture = communityWithMember("치즈", user, "discord-apple", "애플");
        jdbc.execute("ALTER TABLE community_member_scores ADD CONSTRAINT reject_positive_score CHECK (total_score = 0)");
        try {
            assertThatThrownBy(() -> attendanceService.attend(user.getId(), fixture.community().getId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(attendances.count()).isZero();
            assertThat(histories.count()).isZero();
            assertThat(scores.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE community_member_scores DROP CONSTRAINT reject_positive_score");
        }
    }

    @Test
    void rankingSortsStablyIncludesZeroAndKeepsCommunitiesSeparate() throws Exception {
        Fixture mine = communityWithMember("치즈", user, "discord-apple", "애플 서버닉");
        CommunityMember sameScore = members.save(new CommunityMember(mine.community(), "절미"));
        CommunityMember zero = members.save(new CommunityMember(mine.community(), "긴닉네임테스트사용자이름"));
        scoreService.addScore(mine.member(), CommunityScoreType.ATTENDANCE, 3, "테스트 활동",
                CommunityScoreReferenceType.ATTENDANCE, 101L, clock.instant());
        scoreService.addScore(sameScore, CommunityScoreType.ATTENDANCE, 3, "테스트 활동",
                CommunityScoreReferenceType.ATTENDANCE, 102L, clock.instant());

        User other = users.save(new User("다른 사용자"));
        Fixture otherCommunity = communityWithMember("다른 커뮤니티", other, null, "다른 사용자");
        scoreService.addScore(otherCommunity.member(), CommunityScoreType.ATTENDANCE, 50, "다른 점수",
                CommunityScoreReferenceType.ATTENDANCE, 103L, clock.instant());

        CommunityRankingsResponse result = rankingService.getRankings(user.getId(), mine.community().getId());
        assertThat(result.rankings()).extracting(entry -> entry.memberId())
                .containsExactly(mine.member().getId(), sameScore.getId(), zero.getId());
        assertThat(result.rankings()).extracting(entry -> entry.score()).containsExactly(3, 3, 0);
        assertThat(result.rankings()).extracting(entry -> entry.nickname())
                .containsExactly("애플 서버닉", "절미", "긴닉네임테스트사용자이름");
        assertThat(result.myRanking().rank()).isEqualTo(1);
        assertThat(result.myRanking().score()).isEqualTo(3);

        mvc.perform(get(path(mine.community(), "/rankings")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankings.length()").value(3))
                .andExpect(jsonPath("$.rankings[2].score").value(0));
    }

    @Test
    void springStaticUiExposesRankingEntryAndAttendancePage() throws Exception {
        mvc.perform(get("/community-dashboard.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"rankings-link\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"dashboard-attendance-button\"")));
        mvc.perform(get("/rankings.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"attendance-button\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"ranking-list\"")));
    }

    private Fixture communityWithMember(String name, User owner, String discordId, String displayName) {
        Community community = communityService.createCommunity(name, owner.getId());
        CommunityMember member = members.save(new CommunityMember(community, displayName));
        if (discordId != null) {
            memberAccounts.save(new CommunityMemberAccount(
                    member, ExternalAccountProvider.DISCORD, discordId, displayName, displayName, clock.instant()
            ));
        }
        return new Fixture(community, member);
    }

    private String path(Community community, String suffix) {
        return "/api/communities/" + community.getId() + suffix;
    }

    private AttendanceCheckResponse getFuture(Future<AttendanceCheckResponse> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(Community community, CommunityMember member) {}

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-14T03:00:00Z"));
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
