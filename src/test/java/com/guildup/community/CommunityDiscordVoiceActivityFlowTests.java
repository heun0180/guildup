package com.guildup.community;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityDiscordVoiceActivityService;
import com.guildup.community.service.DiscordVoiceActivityRange;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.domain.DiscordVoiceActivityPeriod;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 H2 조회부터 Service 집계, API 기본값까지 함께 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:discord-voice-periods;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Import(CommunityDiscordVoiceActivityFlowTests.FixedClockConfiguration.class)
@Transactional
class CommunityDiscordVoiceActivityFlowTests {
    // 2026-10-07 12:00 KST
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final String DISCORD_USER = "discord-100";

    @Autowired MockMvc mvc;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired UserRepository users;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository accounts;
    @Autowired DiscordCommunityConnectionRepository connections;
    @Autowired DiscordVoiceSessionRepository sessions;
    @Autowired CommunityDiscordVoiceActivityService service;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    private Community community;
    private CommunityMember member;
    private User owner;
    private MockHttpSession ownerSession;

    @BeforeEach
    void setUp() {
        community = communities.save(new Community("음성 활동 테스트"));
        owner = users.save(new User("운영자"));
        memberships.save(new CommunityUser(community, owner, CommunityUserRole.OWNER));
        connections.save(new DiscordCommunityConnection(community, "guild-1", "Guild"));
        member = members.save(new CommunityMember(community, "애플"));
        accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, DISCORD_USER, "apple"));
        ownerSession = new MockHttpSession();
        ownerSession.setAttribute(CurrentUserSession.USER_ID, owner.getId());
    }

    @Test
    void sessionJoinedAndLeftTodayUsesWholeDuration() throws Exception {
        closed("2026-10-07T00:00:00Z", "2026-10-07T02:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.DAY, 7_200, 1, false);
    }

    @Test
    void yesterdayJoinAndTodayLeaveCountsOnlyTwoHoursAfterSeoulMidnight() throws Exception {
        // 10/06 23:00 ~ 10/07 02:00 KST
        var session = closed("2026-10-06T14:00:00Z", "2026-10-06T17:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.DAY, 7_200, 1, false);
        mvc.perform(get(detailPath()).param("period", "DAY").session(ownerSession))
                .andExpect(jsonPath("$.sessions[0].joinedAt").value(session.getJoinedAt().toString()))
                .andExpect(jsonPath("$.sessions[0].leftAt").value(session.getLeftAt().toString()));
    }

    @Test
    void todayJoinWithoutLeaveCountsUntilCurrentTime() throws Exception {
        open("2026-10-07T01:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.DAY, 7_200, 1, true);
    }

    @Test
    void openSessionStartedBeforeTodayIsClippedAtMidnight() throws Exception {
        open("2026-10-06T14:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.DAY, 43_200, 1, true);
    }

    @ParameterizedTest
    @CsvSource({
            "WEEK, 2026-10-03T00:00:00Z, 2026-10-03T01:00:00Z",
            "MONTH, 2026-09-29T00:00:00Z, 2026-09-29T01:00:00Z",
            "YEAR, 2025-12-30T00:00:00Z, 2025-12-30T01:00:00Z"
    })
    void previousWeekMonthAndYearAreExcludedInDatabase(
            DiscordVoiceActivityPeriod period, String joinedAt, String leftAt
    ) throws Exception {
        closed(joinedAt, leftAt);
        assertThat(sessions.findOverlappingSessions(community.getId(), List.of(DISCORD_USER), period.startAt(NOW), NOW))
                .isEmpty();
        assertActivity(period, 0, 0, false);
        mvc.perform(get(path()).param("period", period.name()).session(ownerSession))
                .andExpect(jsonPath("$[0].lastJoinedAt").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DiscordVoiceActivityPeriod.class, names = {"DAY", "WEEK", "MONTH", "YEAR"})
    void sessionSpanningBothPeriodBoundariesIsClipped(
            DiscordVoiceActivityPeriod period
    ) throws Exception {
        Instant start = period.startAt(NOW);
        closed(start.minusSeconds(3_600).toString(), NOW.plusSeconds(3_600).toString());
        assertActivity(period, Duration.between(start, NOW).getSeconds(), 1, false);
    }

    @ParameterizedTest
    @EnumSource(value = DiscordVoiceActivityPeriod.class, names = {"DAY", "WEEK", "MONTH", "YEAR"})
    void touchingBoundariesAndZeroLengthSessionsDoNotContribute(
            DiscordVoiceActivityPeriod period
    ) throws Exception {
        Instant start = period.startAt(NOW);
        closed(start.minusSeconds(60).toString(), start.toString());
        closed(NOW.toString(), NOW.plusSeconds(60).toString());
        closed(start.plusSeconds(10).toString(), start.plusSeconds(10).toString());
        assertActivity(period, 0, 0, false);
    }

    @Test
    void allKeepsFullDurationsAndIncludesRecordsOlderThanFourteenDays() throws Exception {
        closed("2025-12-30T00:00:00Z", "2025-12-30T01:00:00Z");
        closed("2026-10-06T14:00:00Z", "2026-10-06T17:00:00Z");
        open("2026-10-07T01:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.ALL, 21_600, 3, true);
        assertThat(service.getSummaries(owner.getId(), community.getId()))
                .isEqualTo(service.getSummaries(owner.getId(), community.getId(), DiscordVoiceActivityPeriod.ALL));
        assertThat(service.getDetail(owner.getId(), community.getId(), member.getId()))
                .isEqualTo(service.getDetail(owner.getId(), community.getId(), member.getId(), DiscordVoiceActivityPeriod.ALL));
    }

    @Test
    void omittedPeriodDefaultsToAllForBothApis() throws Exception {
        closed("2025-12-30T00:00:00Z", "2025-12-30T01:00:00Z");
        String summaries = mvc.perform(get(path()).session(ownerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalSeconds").value(3_600)).andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get(detailPath()).session(ownerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeconds").value(3_600)).andReturn().getResponse().getContentAsString();
        mvc.perform(get(path()).param("period", "ALL").session(ownerSession)).andExpect(content().json(summaries));
        mvc.perform(get(detailPath()).param("period", "ALL").session(ownerSession)).andExpect(content().json(detail));
    }

    @Test
    void allClampsEndAtNowAndExcludesFutureAndZeroLengthSessions() throws Exception {
        closed(NOW.minusSeconds(60).toString(), NOW.plusSeconds(60).toString());
        closed(NOW.plusSeconds(60).toString(), NOW.plusSeconds(120).toString());
        closed(NOW.minusSeconds(10).toString(), NOW.minusSeconds(10).toString());
        assertActivity(DiscordVoiceActivityPeriod.ALL, 60, 1, false);
    }

    @Test
    void orderingUsesSelectedPeriodTotalsAndKeepsConnectedMembersFirst() throws Exception {
        var second = members.save(new CommunityMember(community, "바나나"));
        accounts.save(new CommunityMemberAccount(second, ExternalAccountProvider.DISCORD, "discord-200", "banana"));
        var connected = members.save(new CommunityMember(community, "체리"));
        accounts.save(new CommunityMemberAccount(connected, ExternalAccountProvider.DISCORD, "discord-300", "cherry"));
        closed("2026-10-01T00:00:00Z", "2026-10-01T06:00:00Z");
        closed(NOW.minusSeconds(60).toString(), NOW.toString());
        saveSession(community, "discord-200", NOW.minusSeconds(120), NOW);
        saveSession(community, "discord-300", NOW.minusSeconds(30), null);

        mvc.perform(get(path()).param("period", "DAY").session(ownerSession))
                .andExpect(jsonPath("$[0].nickname").value("체리"))
                .andExpect(jsonPath("$[1].nickname").value("바나나"))
                .andExpect(jsonPath("$[2].nickname").value("애플"))
                .andExpect(jsonPath("$[2].totalSeconds").value(60));
        mvc.perform(get(path()).param("period", "ALL").session(ownerSession))
                .andExpect(jsonPath("$[0].nickname").value("체리"))
                .andExpect(jsonPath("$[1].nickname").value("애플"));
    }

    @Test
    void queriesAreScopedToCommunityAndDiscordAccount() throws Exception {
        Community other = communities.save(new Community("다른 커뮤니티"));
        saveSession(other, DISCORD_USER, NOW.minusSeconds(600), NOW);
        saveSession(community, "unregistered-discord-user", NOW.minusSeconds(600), NOW);
        closed(NOW.minusSeconds(60).toString(), NOW.toString());
        assertActivity(DiscordVoiceActivityPeriod.ALL, 60, 1, false);
        assertActivity(DiscordVoiceActivityPeriod.DAY, 60, 1, false);
    }

    @Test
    void invalidPeriodReturnsBadRequestForBothApis() throws Exception {
        mvc.perform(get(path()).param("period", "INVALID").session(ownerSession)).andExpect(status().isBadRequest());
        mvc.perform(get(detailPath()).param("period", "INVALID").session(ownerSession)).andExpect(status().isBadRequest());
    }

    @Test
    void existingManagementAndMemberScopeChecksRemain() throws Exception {
        User regular = users.save(new User("일반 회원"));
        memberships.save(new CommunityUser(community, regular, CommunityUserRole.MEMBER));
        var regularSession = new MockHttpSession();
        regularSession.setAttribute(CurrentUserSession.USER_ID, regular.getId());
        mvc.perform(get(path()).param("period", "DAY").session(regularSession)).andExpect(status().isForbidden());
        mvc.perform(get(detailPath()).param("period", "DAY").session(regularSession)).andExpect(status().isForbidden());
        mvc.perform(get(path()).param("period", "DAY")).andExpect(status().isUnauthorized());
        Community other = communities.save(new Community("다른 커뮤니티"));
        CommunityMember foreign = members.save(new CommunityMember(other, "다른 클랜원"));
        mvc.perform(get(path() + "/members/" + foreign.getId()).param("period", "DAY").session(ownerSession))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({"DAY, 2026-10-05", "WEEK, 2026-10-01", "MONTH, 2026-09-15", "YEAR, 2025-06-15"})
    void pastReferenceReadsOnlySelectedPeriodAndClipsBothEdges(
            DiscordVoiceActivityPeriod period, LocalDate referenceDate
    ) throws Exception {
        var range = DiscordVoiceActivityRange.resolve(period, referenceDate, NOW);
        closed(range.start().minusSeconds(3_600).toString(), range.start().plusSeconds(7_200).toString());
        closed(range.end().minusSeconds(3_600).toString(), range.end().plusSeconds(3_600).toString());
        closed(range.start().minusSeconds(7_200).toString(), range.start().minusSeconds(3_600).toString());
        closed(range.end().toString(), range.end().plusSeconds(60).toString());
        assertThat(sessions.findOverlappingSessions(community.getId(), List.of(DISCORD_USER), range.start(), range.end())).hasSize(2);
        assertActivity(period, referenceDate, 10_800, 2, false);
    }

    @ParameterizedTest
    @EnumSource(value = DiscordVoiceActivityPeriod.class, names = {"DAY", "WEEK", "MONTH", "YEAR"})
    void explicitCurrentReferenceKeepsPeriodOnlyApiBehavior(DiscordVoiceActivityPeriod period) throws Exception {
        closed(period.startAt(NOW).minusSeconds(3_600).toString(), NOW.plusSeconds(3_600).toString());
        long expected = Duration.between(period.startAt(NOW), NOW).getSeconds();
        assertActivity(period, LocalDate.parse("2026-10-07"), expected, 1, false);
        String oldSummary = mvc.perform(get(path()).param("period", period.name()).session(ownerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String oldDetail = mvc.perform(get(detailPath()).param("period", period.name()).session(ownerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get(path()).param("period", period.name()).param("referenceDate", "2026-10-07").session(ownerSession))
                .andExpect(content().json(oldSummary));
        mvc.perform(get(detailPath()).param("period", period.name()).param("referenceDate", "2026-10-07").session(ownerSession))
                .andExpect(content().json(oldDetail));
    }

    @Test
    void historicalOpenSessionEndsAtPeriodEndAndDoesNotShowLiveConnectionOrSortFirst() throws Exception {
        LocalDate date = LocalDate.parse("2026-10-05");
        var range = DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.DAY, date, NOW);
        open(range.end().minusSeconds(60).toString());
        var second = members.save(new CommunityMember(community, "바나나"));
        accounts.save(new CommunityMemberAccount(second, ExternalAccountProvider.DISCORD, "discord-200", "banana"));
        saveSession(community, "discord-200", range.start(), range.start().plusSeconds(600));
        mvc.perform(get(path()).param("period", "DAY").param("referenceDate", date.toString()).session(ownerSession))
                .andExpect(jsonPath("$[0].nickname").value("바나나"))
                .andExpect(jsonPath("$[1].totalSeconds").value(60))
                .andExpect(jsonPath("$[1].currentlyConnected").value(false));
        mvc.perform(get(detailPath()).param("period", "DAY").param("referenceDate", date.toString()).session(ownerSession))
                .andExpect(jsonPath("$.totalSeconds").value(60))
                .andExpect(jsonPath("$.sessions[0].durationSeconds").value(60))
                .andExpect(jsonPath("$.sessions[0].leftAt").isEmpty())
                .andExpect(jsonPath("$.sessions[0].currentlyConnected").value(false));
    }

    @Test
    void requestedPastDayCountsTwoHoursForTheUsersBoundaryExample() throws Exception {
        closed("2026-10-04T14:00:00Z", "2026-10-04T17:00:00Z");
        assertActivity(DiscordVoiceActivityPeriod.DAY, LocalDate.parse("2026-10-05"), 7_200, 1, false);
    }

    @ParameterizedTest
    @CsvSource({"DAY, 2026-10-08", "WEEK, 2026-10-12", "MONTH, 2026-11-01", "YEAR, 2027-01-01"})
    void futurePeriodIsRejectedByBothApis(DiscordVoiceActivityPeriod period, String referenceDate) throws Exception {
        mvc.perform(get(path()).param("period", period.name()).param("referenceDate", referenceDate).session(ownerSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get(detailPath()).param("period", period.name()).param("referenceDate", referenceDate).session(ownerSession))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2026-02-30", "not-a-date", "0000-01-01"})
    void invalidReferenceDateIsRejected(String referenceDate) throws Exception {
        mvc.perform(get(path()).param("period", "DAY").param("referenceDate", referenceDate).session(ownerSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get(detailPath()).param("period", "DAY").param("referenceDate", referenceDate).session(ownerSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allRejectsReferenceDateRatherThanApplyingAnUnexpectedFilter() throws Exception {
        mvc.perform(get(path()).param("period", "ALL").param("referenceDate", "2025-01-01").session(ownerSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get(detailPath()).param("period", "ALL").param("referenceDate", "2025-01-01").session(ownerSession))
                .andExpect(status().isBadRequest());
    }

    private void assertActivity(DiscordVoiceActivityPeriod period, long seconds, int count, boolean connected) throws Exception {
        assertActivity(period, null, seconds, count, connected);
    }

    private void assertActivity(DiscordVoiceActivityPeriod period, LocalDate referenceDate, long seconds, int count, boolean connected) throws Exception {
        var summaryRequest = get(path()).param("period", period.name()).session(ownerSession);
        var detailRequest = get(detailPath()).param("period", period.name()).session(ownerSession);
        if (referenceDate != null) {
            summaryRequest.param("referenceDate", referenceDate.toString());
            detailRequest.param("referenceDate", referenceDate.toString());
        }
        mvc.perform(summaryRequest).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].totalSeconds").value(seconds))
                .andExpect(jsonPath("$[0].currentlyConnected").value(connected));
        mvc.perform(detailRequest).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeconds").value(seconds))
                .andExpect(jsonPath("$.sessions.length()").value(count));
        var detail = service.getDetail(owner.getId(), community.getId(), member.getId(), period, referenceDate);
        assertThat(detail.sessions().stream().mapToLong(session -> session.durationSeconds()).sum()).isEqualTo(seconds);
    }

    private DiscordVoiceSession closed(String joinedAt, String leftAt) {
        return saveSession(community, DISCORD_USER, Instant.parse(joinedAt), Instant.parse(leftAt));
    }

    private DiscordVoiceSession open(String joinedAt) {
        return saveSession(community, DISCORD_USER, Instant.parse(joinedAt), null);
    }

    private DiscordVoiceSession saveSession(Community scope, String user, Instant joinedAt, Instant leftAt) {
        var session = new DiscordVoiceSession(scope, "guild-1", user, "voice-1", "배그1", joinedAt);
        if (leftAt != null) session.close(leftAt);
        return sessions.saveAndFlush(session);
    }

    private String path() { return "/api/communities/" + community.getId() + "/discord/voice-activity"; }
    private String detailPath() { return path() + "/members/" + member.getId(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock voiceActivityClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
