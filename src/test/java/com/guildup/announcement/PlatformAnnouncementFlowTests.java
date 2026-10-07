package com.guildup.announcement;

import com.guildup.announcement.domain.*;
import com.guildup.announcement.dto.PlatformAnnouncementRequest;
import com.guildup.announcement.repository.*;
import com.guildup.announcement.service.PlatformAnnouncementService;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.auth.service.SessionCsrfTokens;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:platform-announcements;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "monitoring.cleanup-cron=-"
})
@AutoConfigureMockMvc
@Transactional
class PlatformAnnouncementFlowTests {
    private static final String PUBLIC = "/api/announcements";
    private static final String ADMIN = "/api/developer/announcements";
    private final Instant now = Instant.parse("2026-10-07T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformAnnouncementRepository announcements;
    @Autowired PlatformAnnouncementReadRepository reads;
    @Autowired PlatformAnnouncementService service;
    @Autowired UserRepository users;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityNoticeRepository communityNotices;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean Clock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    private User admin;
    private User user;
    private User owner;
    private User communityAdmin;
    private MockHttpSession adminSession;
    private MockHttpSession userSession;
    private Community community;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
        admin = users.saveAndFlush(new User("개발자"));
        user = users.saveAndFlush(new User("커뮤니티 없는 사용자"));
        owner = users.saveAndFlush(new User("커뮤니티 소유자"));
        communityAdmin = users.saveAndFlush(new User("커뮤니티 운영진"));
        community = communities.save(new Community("별도 커뮤니티"));
        memberships.save(new CommunityUser(community, owner, CommunityUserRole.OWNER));
        memberships.save(new CommunityUser(community, communityAdmin, CommunityUserRole.ADMIN));
        em.flush();
        jdbc.update("update users set system_role = 'SYSTEM_ADMIN' where id = ?", admin.getId());
        em.clear();
        admin = users.findById(admin.getId()).orElseThrow();
        adminSession = session(admin);
        userSession = session(user);
    }

    @Test
    void anyLoggedInUserCanReadPublishedAnnouncementsWithoutCommunityMembership() throws Exception {
        var a = save("전체 사용자", true, null, null, false, false, false, now);
        mvc.perform(get(PUBLIC).session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("전체 사용자"))
                .andExpect(jsonPath("$.content[0].type").value("NOTICE"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].newAnnouncement").value(true))
                .andExpect(jsonPath("$.content[0].content").doesNotExist());
        mvc.perform(get(PUBLIC + "/" + a.getId()).session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("본문"));
        assertThat(reads.count()).isZero();
        verifyNoInteractions(jda, bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "ENDED", "PRIVATE"})
    void hiddenAnnouncementsNeverAppearInListDetailNotificationsOrPopup(String state) throws Exception {
        var a = save(state, !state.equals("PRIVATE"), state.equals("SCHEDULED") ? now.plusSeconds(1) : null,
                state.equals("ENDED") ? now : null, true, true, true, now);
        mvc.perform(get(PUBLIC).session(userSession)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(PUBLIC + "/" + a.getId()).session(userSession)).andExpect(status().isNotFound());
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.recent.length()").value(0));
        mvc.perform(get(PUBLIC + "/popup").session(userSession)).andExpect(status().isNoContent());
        for (String action : new String[]{"read", "popup-confirmation"}) {
            mvc.perform(post(PUBLIC + "/" + a.getId() + "/" + action).session(userSession)
                    .header(SessionCsrfTokens.HEADER, token(userSession))).andExpect(status().isNotFound());
        }
        mvc.perform(get(ADMIN + "/" + a.getId()).session(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.status").value(state));
        assertThat(reads.count()).isZero();
    }

    @Test
    void administratorListsAllStatesAndUpdatesAndDeletesWithoutTouchingCommunityNotices() throws Exception {
        save("게시", true, null, null, false, false, false, now);
        save("예약", true, now.plusSeconds(1), null, false, false, false, now);
        save("종료", true, null, now, false, false, false, now);
        save("비공개", false, null, null, false, false, false, now);
        mvc.perform(get(ADMIN).session(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
        long id = create(request("처음", true, true, true, true, now, now.plusSeconds(100)));
        mvc.perform(put(ADMIN + "/" + id).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession))
                        .contentType("application/json").content(json.writeValueAsString(
                                request("수정", false, false, false, false, null, null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.announcement.title").value("수정"))
                .andExpect(jsonPath("$.announcement.status").value("PRIVATE"))
                .andExpect(jsonPath("$.announcement.createdAt").value(now.toString()));
        mvc.perform(delete(ADMIN + "/" + id).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession)))
                .andExpect(status().isNoContent());
        mvc.perform(get(ADMIN + "/" + id).session(adminSession)).andExpect(status().isNotFound());
        assertThat(communityNotices.count()).isZero();
    }

    @Test
    void rejectsAnonymousRegularCommunityOwnerAndCommunityAdminForEveryAdminOperation() throws Exception {
        var a = save("비공개", false, null, null, false, false, false, now);
        String body = json.writeValueAsString(request("침입", false, false, false, false, null, null));
        for (User denied : new User[]{user, owner, communityAdmin}) {
            var session = session(denied);
            mvc.perform(get(ADMIN).session(session)).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN + "/" + a.getId()).session(session)).andExpect(status().isForbidden());
            mvc.perform(post(ADMIN).session(session).header(SessionCsrfTokens.HEADER, token(session))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(put(ADMIN + "/" + a.getId()).session(session).header(SessionCsrfTokens.HEADER, token(session))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(delete(ADMIN + "/" + a.getId()).session(session).header(SessionCsrfTokens.HEADER, token(session)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/developer/announcements").session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN + "/" + a.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(ADMIN + "/" + a.getId()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(delete(ADMIN + "/" + a.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get(PUBLIC)).andExpect(status().isUnauthorized());
        mvc.perform(get(PUBLIC + "/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get(PUBLIC + "/popup")).andExpect(status().isUnauthorized());
        mvc.perform(post(PUBLIC + "/" + a.getId() + "/read")).andExpect(status().isUnauthorized());
        assertThat(announcements.findById(a.getId()).orElseThrow().getTitle()).isEqualTo("비공개");
    }

    @Test
    void serviceLayerAlsoRejectsDirectNonAdminCalls() {
        var a = save("보호", false, null, null, false, false, false, now);
        var request = request("침입", false, false, false, false, null, null);
        for (Runnable action : new Runnable[]{
                () -> service.adminList(user.getId(), 0, 20), () -> service.adminGet(user.getId(), a.getId()),
                () -> service.create(user.getId(), request), () -> service.update(user.getId(), a.getId(), request),
                () -> service.delete(user.getId(), a.getId())}) {
            assertThatThrownBy(action::run).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
        }
    }

    @Test
    void detailReadIsSparseIdempotentUserScopedAndNewAnnouncementNeedsNoFanOut() throws Exception {
        var a = save("새 소식", true, null, null, true, false, true, now);
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(reads.count()).isZero();
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post(PUBLIC + "/" + a.getId() + "/read").session(userSession)
                            .header(SessionCsrfTokens.HEADER, token(userSession)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.announcement.read").value(true))
                    .andExpect(jsonPath("$.announcement.popupConfirmed").value(false))
                    .andExpect(jsonPath("$.announcement.newAnnouncement").value(true));
            when(clock.instant()).thenReturn(now.plusSeconds(60));
        }
        assertThat(reads.count()).isEqualTo(1);
        assertThat(reads.findByAnnouncement_IdAndUser_Id(a.getId(), user.getId()).orElseThrow().getReadAt()).isEqualTo(now);
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.recent[0].read").value(true));
        mvc.perform(get(PUBLIC + "/notifications").session(session(owner))).andExpect(jsonPath("$.unreadCount").value(1));
        save("추가 소식", true, null, null, false, false, false, now.plusSeconds(60));
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(reads.count()).isEqualTo(1);
    }

    @Test
    void popupRequiresImportantPublishedAndConfirmedOncePerUserAndCascadesOnDelete() throws Exception {
        var a = save("필수 팝업", true, null, null, true, false, true, now);
        save("팝업 아님", true, null, null, true, false, false, now);
        mvc.perform(get(PUBLIC + "/popup").session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.id").value(a.getId()));
        assertThat(reads.count()).isZero();
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post(PUBLIC + "/" + a.getId() + "/popup-confirmation").session(userSession)
                            .header(SessionCsrfTokens.HEADER, token(userSession))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.announcement.popupConfirmed").value(true))
                    .andExpect(jsonPath("$.announcement.read").value(true));
        }
        assertThat(reads.count()).isEqualTo(1);
        mvc.perform(get(PUBLIC + "/popup").session(userSession)).andExpect(status().isNoContent());
        mvc.perform(get(PUBLIC + "/popup").session(session(owner))).andExpect(status().isOk());
        mvc.perform(delete(ADMIN + "/" + a.getId()).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession)))
                .andExpect(status().isNoContent());
        em.flush(); em.clear();
        assertThat(reads.count()).isZero();
    }

    @Test
    void pinsThenImportantThenNewestWhileNotificationsAreRecentFiveAndCountAllUnread() throws Exception {
        save("오래된 고정", true, null, null, false, true, false, now.minusSeconds(100));
        save("이전 중요", true, null, null, true, false, false, now.minusSeconds(90));
        for (int i = 0; i < 7; i++) save("일반 " + i, true, null, null, false, false, false, now.minusSeconds(i));
        mvc.perform(get(PUBLIC).session(userSession)).andExpect(jsonPath("$.content[0].title").value("오래된 고정"))
                .andExpect(jsonPath("$.content[1].title").value("이전 중요"))
                .andExpect(jsonPath("$.content[2].title").value("일반 0"));
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(jsonPath("$.unreadCount").value(9))
                .andExpect(jsonPath("$.recent.length()").value(5)).andExpect(jsonPath("$.recent[0].title").value("일반 0"));
        mvc.perform(get(PUBLIC).param("page", "1").param("size", "2").session(userSession))
                .andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.totalPages").value(5));
    }

    @Test
    void exactPublicationBoundariesAndNewUseFirstPublicationRatherThanDraftCreationOrRead() throws Exception {
        var a = save("예약", true, now.plusSeconds(3600), now.plusSeconds(90000), false, false, false, now);
        when(clock.instant()).thenReturn(now.plusSeconds(3600));
        mvc.perform(get(PUBLIC + "/" + a.getId()).session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.newAnnouncement").value(true));
        when(clock.instant()).thenReturn(now.plusSeconds(89999));
        mvc.perform(get(PUBLIC + "/" + a.getId()).session(userSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.newAnnouncement").value(true));
        when(clock.instant()).thenReturn(now.plusSeconds(90000));
        mvc.perform(get(PUBLIC + "/" + a.getId()).session(userSession)).andExpect(status().isNotFound());
        var draft = save("오래된 초안", false, null, null, false, false, false, now.minusSeconds(172800));
        when(clock.instant()).thenReturn(now);
        service.update(admin.getId(), draft.getId(), request("첫 공개", false, false, false, true, null, null));
        mvc.perform(get(PUBLIC + "/" + draft.getId()).session(userSession)).andExpect(jsonPath("$.announcement.newAnnouncement").value(true));
        when(clock.instant()).thenReturn(now.plusSeconds(86400));
        mvc.perform(get(PUBLIC + "/" + draft.getId()).session(userSession)).andExpect(jsonPath("$.announcement.newAnnouncement").value(false))
                .andExpect(jsonPath("$.announcement.read").value(false));
        service.update(admin.getId(), draft.getId(), request("수정", false, false, false, true, null, null));
        mvc.perform(get(PUBLIC + "/" + draft.getId()).session(userSession)).andExpect(jsonPath("$.announcement.newAnnouncement").value(false));
    }

    @Test
    void validatesAllInputTypesLengthsPeriodsAndPopupFlagsAndPagination() throws Exception {
        var valid = request("정상", false, false, false, true, now, now.plusSeconds(60));
        var base = json.writeValueAsString(valid);
        for (String invalid : new String[]{
                base.replace("정상", " "), base.replace("정상", "x".repeat(201)),
                base.replace("본문", " "), base.replace("본문", "x".repeat(20001)),
                base.replace("NOTICE", "UNKNOWN"), base.replace("\"NOTICE\"", "null"),
                base.replace(now.plusSeconds(60).toString(), now.toString()),
                base.replace(now.plusSeconds(60).toString(), now.minusSeconds(1).toString()),
                base.replace(now.toString(), "invalid-date"), base.replace("\"popup\":false", "\"popup\":true")}) {
            mvc.perform(post(ADMIN).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession))
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        for (PlatformAnnouncementType type : PlatformAnnouncementType.values()) {
            create(new PlatformAnnouncementRequest("정상", "본문", type, false, false, false, true, null, null));
        }
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            mvc.perform(get(PUBLIC + query).session(userSession)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void csrfTokensAreRequiredForAdminWritesAndReadAndPopupConfirmation() throws Exception {
        var a = save("보안", true, null, null, true, false, true, now);
        mvc.perform(post(ADMIN).session(adminSession).contentType("application/json")
                        .content(json.writeValueAsString(request("생성", false, false, false, true, null, null))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        mvc.perform(delete(ADMIN + "/" + a.getId()).session(adminSession)).andExpect(status().isForbidden());
        for (String action : new String[]{"read", "popup-confirmation"}) {
            mvc.perform(post(PUBLIC + "/" + a.getId() + "/" + action).session(userSession)).andExpect(status().isForbidden());
        }
        assertThat(reads.count()).isZero();
    }

    @Test
    void futureAudienceTypesAreNotAccidentallyBroadcastToEveryone() throws Exception {
        var game = save("게임 한정", true, null, null, true, false, true, now);
        var specific = save("특정 커뮤니티", true, null, null, true, false, true, now);
        em.flush();
        jdbc.update("update platform_announcements set target_type = 'GAME', target_game_type = 'BATTLEGROUNDS_STEAM' where id = ?", game.getId());
        jdbc.update("update platform_announcements set target_type = 'COMMUNITY', target_community_id = ? where id = ?", community.getId(), specific.getId());
        em.clear();
        mvc.perform(get(PUBLIC).session(userSession)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(PUBLIC + "/notifications").session(userSession)).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(get(PUBLIC + "/popup").session(userSession)).andExpect(status().isNoContent());
        mvc.perform(get(PUBLIC + "/" + game.getId()).session(userSession)).andExpect(status().isNotFound());
        mvc.perform(get(ADMIN).session(adminSession)).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void platformAnnouncementsAndCommunityNoticesRemainIndependentInDataAndPermissions() throws Exception {
        var notice = communityNotices.save(new CommunityNotice(community, owner, "커뮤니티 공지", "멤버만", true, true, now));
        var a = save("서비스 공지", true, null, null, false, false, false, now);
        mvc.perform(get(PUBLIC).session(userSession)).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("서비스 공지"));
        String communityPath = "/api/communities/" + community.getId() + "/notices";
        mvc.perform(get(communityPath).session(session(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("커뮤니티 공지"));
        mvc.perform(get(communityPath).session(userSession)).andExpect(status().isForbidden());
        mvc.perform(get(communityPath).session(adminSession)).andExpect(status().isForbidden());
        mvc.perform(delete(ADMIN + "/" + a.getId()).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession)))
                .andExpect(status().isNoContent());
        assertThat(communityNotices.findById(notice.getId())).isPresent();
    }

    private PlatformAnnouncement save(String title, boolean published, Instant start, Instant end,
                                      boolean important, boolean pinned, boolean popup, Instant created) {
        return announcements.save(new PlatformAnnouncement(admin, title, "본문", PlatformAnnouncementType.NOTICE,
                important, pinned, popup, published, start, end, created));
    }
    private PlatformAnnouncementRequest request(String title, boolean important, boolean pinned, boolean popup,
                                                boolean published, Instant start, Instant end) {
        return new PlatformAnnouncementRequest(title, "본문", PlatformAnnouncementType.NOTICE, important, pinned, popup, published, start, end);
    }
    private long create(PlatformAnnouncementRequest request) throws Exception {
        var result = mvc.perform(post(ADMIN).session(adminSession).header(SessionCsrfTokens.HEADER, token(adminSession))
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readValue(result, Map.class).get("announcement") instanceof Map<?, ?> a
                ? ((Number) a.get("id")).longValue() : -1;
    }
    private MockHttpSession session(User user) {
        var session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }
    private String token(MockHttpSession session) { return SessionCsrfTokens.getOrCreate(session); }
}
