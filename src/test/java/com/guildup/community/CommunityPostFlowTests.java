package com.guildup.community;

import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-posts;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class CommunityPostFlowTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityPostRepository posts;
    @Autowired UserRepository users;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean Clock clock;

    private final Instant now = Instant.parse("2026-09-18T10:00:00Z");
    private Community community;
    private Community otherCommunity;
    private User member;
    private User secondMember;
    private User admin;
    private User outsider;
    private MockHttpSession memberSession;
    private MockHttpSession secondSession;
    private MockHttpSession adminSession;
    private MockHttpSession outsiderSession;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
        community = communities.save(new Community("게시판 커뮤니티"));
        otherCommunity = communities.save(new Community("다른 커뮤니티"));
        member = users.save(new User("애플"));
        secondMember = users.save(new User("절미"));
        admin = users.save(new User("운영진"));
        outsider = users.save(new User("외부인"));
        memberships.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));
        memberships.save(new CommunityUser(community, secondMember, CommunityUserRole.MEMBER));
        memberships.save(new CommunityUser(community, admin, CommunityUserRole.ADMIN));
        memberships.save(new CommunityUser(otherCommunity, member, CommunityUserRole.MEMBER));
        memberSession = session(member);
        secondSession = session(secondMember);
        adminSession = session(admin);
        outsiderSession = session(outsider);
    }

    @Test
    void memberCreatesAndListsPostWithoutDiscordDependency() throws Exception {
        long id = create(memberSession, postBody("FREE", "첫 게시글", false, false));
        mvc.perform(get(base()).session(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].authorName").value("애플"))
                .andExpect(jsonPath("$.content[0].authorRole").value("MEMBER"))
                .andExpect(jsonPath("$.content[0].commentCount").value(0));
        assertThat(posts.findById(id).orElseThrow().getAuthorCommunityUser().getUser().getId()).isEqualTo(member.getId());
        verifyNoInteractions(jda);
    }

    @Test
    void outsiderCannotCreateOrRead() throws Exception {
        mvc.perform(post(base()).session(outsiderSession).contentType("application/json")
                .content(postBody("FREE", "접근 금지", false, false))).andExpect(status().isForbidden());
        mvc.perform(get(base()).session(outsiderSession)).andExpect(status().isForbidden());
    }

    @Test
    void authorUpdatesPostButOtherMemberCannot() throws Exception {
        long id = create(memberSession, postBody("FREE", "원래 제목", false, false));
        String update = "{\"category\":\"QUESTION\",\"title\":\"수정 제목\",\"content\":\"수정 본문\"}";
        mvc.perform(patch(base() + "/" + id).session(memberSession).contentType("application/json").content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정 제목"))
                .andExpect(jsonPath("$.category").value("QUESTION"));
        mvc.perform(patch(base() + "/" + id).session(secondSession).contentType("application/json").content(update))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDeletesOtherPostButMemberCannot() throws Exception {
        long id = create(memberSession, postBody("FREE", "삭제 권한", false, false));
        mvc.perform(delete(base() + "/" + id).session(secondSession)).andExpect(status().isForbidden());
        mvc.perform(delete(base() + "/" + id).session(adminSession)).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + id).session(memberSession)).andExpect(status().isNotFound());
        assertThat(posts.findById(id).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void onlyManagerCanSetNoticeAndPin() throws Exception {
        mvc.perform(post(base()).session(memberSession).contentType("application/json")
                .content(postBody("FREE", "권한 위반", true, false))).andExpect(status().isForbidden());
        long id = create(adminSession, postBody("FREE", "운영진 공지", true, true));
        mvc.perform(get(base() + "/" + id).session(memberSession))
                .andExpect(jsonPath("$.notice").value(true)).andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(patch(base() + "/" + id).session(memberSession).contentType("application/json")
                .content("{\"notice\":false}")).andExpect(status().isForbidden());
        mvc.perform(patch(base() + "/" + id).session(adminSession).contentType("application/json")
                .content("{\"notice\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value(false));
    }

    @Test
    void postIdIsAlwaysScopedToCommunity() throws Exception {
        long id = create(memberSession, postBody("FREE", "우리 게시글", false, false));
        String foreignBase = "/api/communities/" + otherCommunity.getId() + "/posts";
        mvc.perform(get(foreignBase + "/" + id).session(memberSession)).andExpect(status().isNotFound());
        mvc.perform(patch(foreignBase + "/" + id).session(memberSession).contentType("application/json")
                .content("{\"category\":\"FREE\",\"title\":\"침범 시도\",\"content\":\"실패해야 합니다\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(foreignBase + "/" + id).session(memberSession)).andExpect(status().isNotFound());
    }

    @Test
    void commentAuthorCanUpdateAndDeleteButOtherMemberCannot() throws Exception {
        long postId = create(memberSession, postBody("FREE", "댓글 글", false, false));
        long commentId = createComment(postId, secondSession, "첫 댓글");
        mvc.perform(patch(commentPath(postId, commentId)).session(memberSession).contentType("application/json")
                .content("{\"content\":\"가로채기\"}")).andExpect(status().isForbidden());
        mvc.perform(patch(commentPath(postId, commentId)).session(secondSession).contentType("application/json")
                .content("{\"content\":\"수정 댓글\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("수정 댓글"));
        mvc.perform(delete(commentPath(postId, commentId)).session(secondSession)).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + postId).session(memberSession))
                .andExpect(jsonPath("$.comments.length()").value(0));
    }

    @Test
    void adminCanDeleteOtherComment() throws Exception {
        long postId = create(memberSession, postBody("FREE", "댓글 관리", false, false));
        long commentId = createComment(postId, secondSession, "관리 대상");
        mvc.perform(delete(commentPath(postId, commentId)).session(adminSession)).andExpect(status().isNoContent());
    }

    @Test
    void listFiltersPaginatesOrdersAndCountsComments() throws Exception {
        long normal = create(memberSession, postBody("FREE", "일반 글", false, false));
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        createComment(normal, secondSession, "댓글");
        long notice = create(adminSession, postBody("QUESTION", "공지 글", true, false));
        long pinned = create(adminSession, postBody("PARTY", "고정 글", false, true));
        mvc.perform(get(base() + "?page=0&size=2").session(memberSession))
                .andExpect(jsonPath("$.content[0].id").value(pinned))
                .andExpect(jsonPath("$.content[1].id").value(notice))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(base() + "?category=FREE&page=0&size=20").session(memberSession))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].commentCount").value(1));
    }

    @Test
    void duplicateDetailRequestWithSameViewTokenCountsOnce() throws Exception {
        long id = create(memberSession, postBody("FREE", "조회수", false, false));
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(base() + "/" + id).session(memberSession).header("X-View-Token", "same-entry"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewCount").value(1));
        }
        mvc.perform(get(base() + "/" + id).session(memberSession).header("X-View-Token", "next-entry"))
                .andExpect(jsonPath("$.viewCount").value(2));
    }

    @Test
    void validatesPostCommentAndPagingBounds() throws Exception {
        mvc.perform(post(base()).session(memberSession).contentType("application/json")
                .content(postBody("FREE", "x", false, false))).andExpect(status().isBadRequest());
        mvc.perform(post(base()).session(memberSession).contentType("application/json")
                .content(postBody("FREE", "정상 제목", false, false).replace("본문 내용", "x".repeat(20001))))
                .andExpect(status().isBadRequest());
        long id = create(memberSession, postBody("FREE", "댓글 검증", false, false));
        mvc.perform(post(base() + "/" + id + "/comments").session(memberSession).contentType("application/json")
                .content("{\"content\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "?size=51").session(memberSession)).andExpect(status().isBadRequest());
    }

    private long create(MockHttpSession session, String body) throws Exception {
        String response = mvc.perform(post(base()).session(session).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long createComment(long postId, MockHttpSession session, String content) throws Exception {
        String response = mvc.perform(post(base() + "/" + postId + "/comments").session(session)
                        .contentType("application/json").content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("id").asLong();
    }

    private String postBody(String category, String title, boolean notice, boolean pinned) {
        return "{\"category\":\"" + category + "\",\"title\":\"" + title
                + "\",\"content\":\"본문 내용\",\"notice\":" + notice + ",\"pinned\":" + pinned + "}";
    }

    private String base() { return "/api/communities/" + community.getId() + "/posts"; }
    private String commentPath(long postId, long commentId) { return base() + "/" + postId + "/comments/" + commentId; }
    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("LOGIN_USER_ID", user.getId());
        return session;
    }
}
