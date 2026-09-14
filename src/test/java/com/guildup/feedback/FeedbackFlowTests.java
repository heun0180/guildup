package com.guildup.feedback;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.feedback.exception.FeedbackMailException;
import com.guildup.feedback.service.FeedbackMailMessage;
import com.guildup.feedback.service.FeedbackMailService;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:feedback;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class FeedbackFlowTests {
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-14T18:30:00Z");

    @Autowired MockMvc mvc;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository externalAccounts;
    @MockitoBean FeedbackMailService mailService;
    @MockitoBean Clock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    private Community community;
    private User member;
    private User outsider;
    private MockHttpSession memberSession;
    private MockHttpSession outsiderSession;

    @BeforeEach
    void setUp() {
        reset(mailService);
        when(clock.instant()).thenReturn(RECEIVED_AT);
        member = users.save(new User("권성현"));
        outsider = users.save(new User("외부인"));
        community = communities.save(new Community("치즈 클랜"));
        memberships.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));
        memberSession = session(member);
        outsiderSession = session(outsider);
    }

    @ParameterizedTest
    @CsvSource({
            "FEATURE,기능 건의,랭킹 기능을 추가해주세요",
            "BUG,버그 제보,활동 조회가 되지 않습니다",
            "ETC,기타 문의,문의드립니다"
    })
    void sendsEveryFeedbackType(String type, String typeName, String title) throws Exception {
        externalAccounts.save(new UserExternalAccount(
                member, ExternalAccountProvider.DISCORD, "123456789012345678", "애플"));

        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body(type, title, "사용자가 작성한 내용입니다.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("소중한 의견 감사합니다. 개발자에게 전달되었습니다."));

        var captor = org.mockito.ArgumentCaptor.forClass(FeedbackMailMessage.class);
        verify(mailService).send(captor.capture());
        FeedbackMailMessage message = captor.getValue();
        assertThat(message.subject()).isEqualTo("[GuildUp][%s][치즈 클랜] %s".formatted(typeName, title));
        assertThat(message.body()).contains(
                "문의 유형: " + typeName,
                "커뮤니티: 치즈 클랜",
                "Community ID: " + community.getId(),
                "사용자: 권성현",
                "GuildUp User ID: " + member.getId(),
                "커뮤니티 권한: MEMBER",
                "Discord 닉네임: 애플",
                "Discord User ID: 123456789012345678",
                "접수 시간: 2026-09-15 03:30:00",
                title,
                "사용자가 작성한 내용입니다.");
    }

    @Test
    void sendsWithoutDiscordConnection() throws Exception {
        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body("ETC", "Discord 없이 문의", "문의 내용")))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(FeedbackMailMessage.class);
        verify(mailService).send(captor.capture());
        assertThat(captor.getValue().body()).contains(
                "Discord 닉네임: 연결 안 됨",
                "Discord User ID: 연결 안 됨");
    }

    @Test
    void ownerAdminAndMemberCanAllSendFeedback() throws Exception {
        for (CommunityUserRole role : CommunityUserRole.values()) {
            User user = users.save(new User(role.name()));
            memberships.save(new CommunityUser(community, user, role));
            mvc.perform(post(path(community)).session(session(user)).contentType("application/json")
                            .content(body("FEATURE", "역할별 문의", "문의 내용")))
                    .andExpect(status().isOk());
        }
        verify(mailService, times(3)).send(any());
    }

    @Test
    void rejectsMissingAndBlankTitle() throws Exception {
        for (String body : new String[]{
                "{\"type\":\"BUG\",\"content\":\"내용\"}",
                body("BUG", "   ", "내용")
        }) {
            mvc.perform(post(path(community)).session(memberSession).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsMissingAndBlankContent() throws Exception {
        for (String body : new String[]{
                "{\"type\":\"BUG\",\"title\":\"제목\"}",
                body("BUG", "제목", "   ")
        }) {
            mvc.perform(post(path(community)).session(memberSession).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsExcessiveTitleAndContent() throws Exception {
        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body("BUG", "x".repeat(101), "내용")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body("BUG", "제목", "x".repeat(3001))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsInvalidOrMissingType() throws Exception {
        for (String body : new String[]{
                "{\"type\":\"UNKNOWN\",\"title\":\"제목\",\"content\":\"내용\"}",
                "{\"title\":\"제목\",\"content\":\"내용\"}"
        }) {
            mvc.perform(post(path(community)).session(memberSession).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsHeaderInjectionInTitle() throws Exception {
        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body("BUG", "제목\\r\\nBcc: attacker@example.com", "내용")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailService);
    }

    @Test
    void returnsNotFoundForMissingCommunity() throws Exception {
        mvc.perform(post("/api/communities/999999/feedback").session(memberSession)
                        .contentType("application/json").content(body("BUG", "제목", "내용")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("커뮤니티를 찾을 수 없습니다."));
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsNonMemberAndAnonymousUser() throws Exception {
        mvc.perform(post(path(community)).session(outsiderSession).contentType("application/json")
                        .content(body("BUG", "제목", "내용")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path(community)).contentType("application/json")
                        .content(body("BUG", "제목", "내용")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mailService);
    }

    @Test
    void hidesMailFailureDetailsFromClient() throws Exception {
        doThrow(new FeedbackMailException(new IllegalStateException("SMTP password rejected")))
                .when(mailService).send(any());

        mvc.perform(post(path(community)).session(memberSession).contentType("application/json")
                        .content(body("BUG", "제목", "내용")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("문의 전송에 실패했습니다."));
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }

    private String path(Community community) {
        return "/api/communities/" + community.getId() + "/feedback";
    }

    private String body(String type, String title, String content) {
        return "{\"type\":\"%s\",\"title\":\"%s\",\"content\":\"%s\"}"
                .formatted(type, title, content);
    }
}
