package com.guildup.community;

import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-news;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class CommunityNewsFlowTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired UserRepository users;
    @Autowired CommunityNoticeRepository notices;
    @Autowired CommunityEventRepository events;
    @Autowired DiscordCommunityConnectionRepository connections;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean Clock clock;
    private final Instant now = Instant.parse("2026-09-11T12:00:00Z");
    private Community community;
    private Community otherCommunity;
    private User owner;
    private MockHttpSession ownerSession;
    private MockHttpSession adminSession;
    private MockHttpSession memberSession;
    private MockHttpSession outsiderSession;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
        owner = users.save(new User("작성자"));
        User admin = users.save(new User("운영진"));
        User member = users.save(new User("클랜원"));
        User outsider = users.save(new User("외부인"));
        community = communities.save(new Community("Discord 없는 커뮤니티"));
        otherCommunity = communities.save(new Community("다른 커뮤니티"));
        memberships.save(new CommunityUser(community, owner, CommunityUserRole.OWNER));
        memberships.save(new CommunityUser(otherCommunity, owner, CommunityUserRole.OWNER));
        memberships.save(new CommunityUser(community, admin, CommunityUserRole.ADMIN));
        memberships.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));
        ownerSession = session(owner);
        adminSession = session(admin);
        memberSession = session(member);
        outsiderSession = session(outsider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"notices", "events"})
    void createsListsReadsUpdatesAndDeletesWithoutDiscord(String type) throws Exception {
        String base = path(community, type);
        long id = create(base, body(type, "첫 소식"), ownerSession);
        mvc.perform(get(base).session(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].communityId").value(community.getId()))
                .andExpect(jsonPath("$[0].authorName").value("작성자"));
        mvc.perform(get(base + "/" + id).session(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("첫 소식"))
                .andExpect(jsonPath("$.createdAt").value(now.toString()));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        mvc.perform(put(base + "/" + id).session(adminSession).contentType("application/json")
                        .content(body(type, "수정한 소식")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정한 소식"))
                .andExpect(jsonPath("$.authorId").value(owner.getId()))
                .andExpect(jsonPath("$.createdAt").value(now.toString()))
                .andExpect(jsonPath("$.updatedAt").value(now.plusSeconds(60).toString()));
        mvc.perform(get(base + "/" + id).session(memberSession))
                .andExpect(jsonPath("$.title").value("수정한 소식"));
        mvc.perform(delete(base + "/" + id).session(adminSession)).andExpect(status().isNoContent());
        mvc.perform(get(base + "/" + id).session(ownerSession)).andExpect(status().isNotFound());
        mvc.perform(get(base).session(memberSession)).andExpect(jsonPath("$.length()").value(0));
        assertThat(connections.count()).isZero();
        verifyNoInteractions(jda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"notices", "events"})
    void memberCannotCreateUpdateOrDeleteAndOutsiderCannotRead(String type) throws Exception {
        String base = path(community, type);
        long id = create(base, body(type, "보호된 소식"), adminSession);
        mvc.perform(post(base).session(memberSession).contentType("application/json").content(body(type, "금지")))
                .andExpect(status().isForbidden());
        mvc.perform(put(base + "/" + id).session(memberSession).contentType("application/json").content(body(type, "금지")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(base + "/" + id).session(memberSession)).andExpect(status().isForbidden());
        mvc.perform(get(base).session(outsiderSession)).andExpect(status().isForbidden());
        mvc.perform(get(base + "/" + id).session(outsiderSession)).andExpect(status().isForbidden());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(post(base).contentType("application/json").content(body(type, "금지")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(base + "/" + id).session(ownerSession)).andExpect(jsonPath("$.title").value("보호된 소식"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notices", "events"})
    void scopesListAndAllItemOperationsToCommunityEvenForOwnerOfBoth(String type) throws Exception {
        String base = path(community, type);
        long foreignId = create(path(otherCommunity, type), body(type, "다른 커뮤니티 소식"), ownerSession);
        create(base, body(type, "우리 소식"), ownerSession);
        mvc.perform(get(base).session(ownerSession)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("우리 소식"));
        mvc.perform(get(base + "/" + foreignId).session(ownerSession)).andExpect(status().isNotFound());
        mvc.perform(put(base + "/" + foreignId).session(ownerSession).contentType("application/json")
                .content(body(type, "침범"))).andExpect(status().isNotFound());
        mvc.perform(delete(base + "/" + foreignId).session(ownerSession)).andExpect(status().isNotFound());
        mvc.perform(get(path(otherCommunity, type) + "/" + foreignId).session(ownerSession))
                .andExpect(jsonPath("$.title").value("다른 커뮤니티 소식"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notices", "events"})
    void validatesTitleAndContentLength(String type) throws Exception {
        for (String title : new String[]{"", "   ", "x".repeat(201)}) {
            mvc.perform(post(path(community, type)).session(ownerSession).contentType("application/json")
                    .content(body(type, title))).andExpect(status().isBadRequest());
        }
        mvc.perform(post(path(community, type)).session(ownerSession).contentType("application/json")
                .content(body(type, "길이 검증").replace("본문", "x".repeat(20001))))
                .andExpect(status().isBadRequest());
        if (type.equals("notices")) {
            mvc.perform(post(path(community, type)).session(ownerSession).contentType("application/json")
                    .content(body(type, "본문 필수").replace("본문", "   "))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void pinnedNoticesComeFirstThenNewestAndFlagsCanBeEdited() throws Exception {
        notices.save(new CommunityNotice(community, owner, "오래된 고정", "내용", true, true, now.minusSeconds(100)));
        notices.save(new CommunityNotice(community, owner, "이전 일반", "내용", false, false, now.minusSeconds(50)));
        var latest = notices.save(new CommunityNotice(community, owner, "최신 일반", "내용", false, false, now));
        mvc.perform(get(path(community, "notices")).session(memberSession))
                .andExpect(jsonPath("$[0].title").value("오래된 고정"))
                .andExpect(jsonPath("$[1].title").value("최신 일반"))
                .andExpect(jsonPath("$[2].title").value("이전 일반"));
        mvc.perform(put(path(community, "notices") + "/" + latest.getId()).session(ownerSession)
                .contentType("application/json").content(body("notices", "새 고정").replace("false", "true")))
                .andExpect(jsonPath("$.important").value(true)).andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(get(path(community, "notices")).session(memberSession))
                .andExpect(jsonPath("$[0].title").value("새 고정"));
    }

    @Test
    void eventDatesAndTypeAreValidatedForCreateAndUpdate() throws Exception {
        String base = path(community, "events");
        String valid = body("events", "이벤트");
        long id = create(base, valid, ownerSession);
        for (String invalid : new String[]{
                valid.replace("2026-09-12T14:00:00Z", "2026-09-10T14:00:00Z"),
                valid.replace("\"2026-09-12T12:00:00Z\"", "null"),
                valid.replace("\"2026-09-12T14:00:00Z\"", "null"),
                valid.replace("2026-09-12T12:00:00Z", "invalid-date"),
                valid.replace("GENERAL", "UNKNOWN"), valid.replace("\"GENERAL\"", "null")}) {
            mvc.perform(post(base).session(ownerSession).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(base + "/" + id).session(ownerSession).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(base + "/" + id).session(ownerSession).contentType("application/json")
                        .content(valid.replace("GENERAL", "CLAN_MATCH").replace("2026-09-12T14:00:00Z", "2026-09-12T12:00:00Z")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("CLAN_MATCH"));
        mvc.perform(get(base + "/" + id).session(memberSession))
                .andExpect(jsonPath("$.endAt").value("2026-09-12T12:00:00Z"));
    }

    @Test
    void computesStatusAtExactBoundariesAndOrdersFeed() throws Exception {
        var ended = event("종료", now.minusSeconds(60), now.minusSeconds(1));
        var upcoming = event("예정", now.plusSeconds(60), now.plusSeconds(120));
        var ongoing = event("진행", now, now.plusSeconds(30));
        String base = path(community, "events");
        mvc.perform(get(base).session(memberSession))
                .andExpect(jsonPath("$[0].id").value(ongoing.getId()))
                .andExpect(jsonPath("$[0].status").value("ONGOING"))
                .andExpect(jsonPath("$[1].id").value(upcoming.getId()))
                .andExpect(jsonPath("$[1].status").value("UPCOMING"))
                .andExpect(jsonPath("$[2].id").value(ended.getId()))
                .andExpect(jsonPath("$[2].status").value("ENDED"));
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        mvc.perform(get(base + "/" + ongoing.getId()).session(memberSession)).andExpect(jsonPath("$.status").value("ONGOING"));
        when(clock.instant()).thenReturn(now.plusSeconds(31));
        mvc.perform(get(base + "/" + ongoing.getId()).session(memberSession)).andExpect(jsonPath("$.status").value("ENDED"));
    }

    @Test
    void summaryIsLimitedScopedAndExcludesPastEvents() throws Exception {
        for (int i = 1; i <= 5; i++) {
            notices.save(new CommunityNotice(community, owner, "공지 " + i, "본문", false, false, now.plusSeconds(i)));
            event("예정 " + i, now.plusSeconds(i * 60), now.plusSeconds(i * 120));
        }
        event("진행 중", now.minusSeconds(10), now.plusSeconds(10));
        event("종료", now.minusSeconds(20), now.minusSeconds(10));
        notices.save(new CommunityNotice(otherCommunity, owner, "다른 공지", "본문", true, true, now));
        String url = "/api/communities/" + community.getId() + "/news-summary";
        mvc.perform(get(url).session(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notices.length()").value(3))
                .andExpect(jsonPath("$.notices[0].title").value("공지 5"))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].title").value("예정 1"));
        mvc.perform(get(url).session(outsiderSession)).andExpect(status().isForbidden());
        verifyNoInteractions(jda);
    }

    private CommunityEvent event(String title, Instant start, Instant end) {
        return events.save(new CommunityEvent(community, owner, title, "설명", CommunityEventType.GENERAL, start, end, now));
    }

    private long create(String path, String body, MockHttpSession session) throws Exception {
        String response = mvc.perform(post(path).session(session).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private String body(String type, String title) {
        return "{\"title\":\"" + title + "\",\"content\":\"본문\"," + (type.equals("notices")
                ? "\"important\":false,\"pinned\":false}"
                : "\"type\":\"GENERAL\",\"startAt\":\"2026-09-12T12:00:00Z\",\"endAt\":\"2026-09-12T14:00:00Z\"}");
    }

    private String path(Community target, String type) { return "/api/communities/" + target.getId() + "/" + type; }

    private MockHttpSession session(User user) {
        var session = new MockHttpSession();
        session.setAttribute("LOGIN_USER_ID", user.getId());
        return session;
    }
}
