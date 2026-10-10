package com.guildup.developer;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.developer.service.DeveloperUserQueryService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import jakarta.persistence.EntityManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-users;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false", "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret"
})
@AutoConfigureMockMvc
@Transactional
class DeveloperUserManagementTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean NamedParameterJdbcTemplate namedJdbc;
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository external;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired DeveloperUserQueryService queries;
    @Autowired EntityManager em;
    @MockitoSpyBean Clock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordApiClient discord;
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-10T03:00:00Z");
    User admin, email, discordUser, both, withdrawn, noAccount;

    @BeforeEach void fixtures() {
        when(clock.instant()).thenReturn(now);
        admin = user("관리자", now.minusSeconds(40 * 86400L));
        jdbc.update("update users set system_role = 'SYSTEM_ADMIN' where id = ?", admin.getId());
        email = user("이메일 회원", now.minusSeconds(3600));
        discordUser = user("Discord 회원", Instant.parse("2026-10-03T15:00:00Z")); // first second of the 7-day window in KST
        both = user("연결 회원", Instant.parse("2026-10-03T14:59:59Z"));
        withdrawn = user("탈퇴 전 닉네임", now.minusSeconds(3600));
        noAccount = user("기록 없는 회원", now.minusSeconds(40 * 86400L));
        credentials.saveAndFlush(new UserCredential(email, "email@example.com", "SECRET_TEST_HASH"));
        credentials.saveAndFlush(new UserCredential(both, "linked@example.com", "SECRET_LINKED_HASH"));
        external.saveAndFlush(new UserExternalAccount(discordUser, ExternalAccountProvider.DISCORD, "discord-only", "discord-name"));
        external.saveAndFlush(new UserExternalAccount(both, ExternalAccountProvider.DISCORD, "discord-linked", "linked-name"));
        jdbc.update("update users set status = 'WITHDRAWN', nickname = '탈퇴한 사용자' where id = ?", withdrawn.getId());
        jdbc.update("update user_external_accounts set linked_at = null where user_id = ?", discordUser.getId());
        em.clear();
    }

    @Test void methodsSearchAndMissingRecordsAreAccurateAndSafe() throws Exception {
        for (var entry : List.of(new Object[]{"EMAIL", email}, new Object[]{"DISCORD", discordUser}, new Object[]{"EMAIL_DISCORD", both})) {
            mvc.perform(get("/api/developer/users").param("loginMethod", (String) entry[0]).session(session(admin)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(((User) entry[1]).getId()))
                    .andExpect(jsonPath("$.content[0].lastLoginAt").isEmpty()).andExpect(jsonPath("$.content[0].lastActiveAt").isEmpty());
        }
        for (String q : List.of(email.getId().toString(), "이메일 회원", "EMAIL@EXAMPLE.COM")) {
            mvc.perform(get("/api/developer/users").param("q", q).session(session(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].communityCount").value(0));
        }
        mvc.perform(get("/api/developer/users").param("q", "%_").session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        var response = mvc.perform(get("/api/developer/users/{id}", both.getId()).session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginAccounts.length()").value(2))
                .andExpect(jsonPath("$.loginAccounts[0].provider").value("EMAIL"))
                .andExpect(jsonPath("$.loginAccounts[0].externalUserId").isEmpty())
                .andExpect(jsonPath("$.loginAccounts[1].externalUserId").value("discord-linked"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password", "token", "authenticationVersion", "SECRET_", "birthDate");
        mvc.perform(get("/api/developer/users/{id}", discordUser.getId()).session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginAccounts[0].linkedAt").isEmpty());
        mvc.perform(get("/api/developer/users/{id}", noAccount.getId()).session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.communities.length()").value(0))
                .andExpect(jsonPath("$.user.lastLoginAt").isEmpty());
    }

    @Test void detailPreservesCommunityRolesNativeNicknameDiscordFallbackAndEndedMembership() throws Exception {
        Community first = communities.saveAndFlush(new Community("운영 커뮤니티"));
        Community second = communities.saveAndFlush(new Community("일반 커뮤니티"));
        Community third = communities.saveAndFlush(new Community("종료된 커뮤니티"));
        CommunityMember nativeMember = members.saveAndFlush(new CommunityMember(first, "내부 클랜 닉네임"));
        var owner = new CommunityUser(first, both, CommunityUserRole.OWNER); owner.linkCommunityMember(nativeMember);
        memberships.saveAndFlush(owner);
        CommunityMember discordMember = members.saveAndFlush(new CommunityMember(second, "Discord 클랜 닉네임"));
        memberAccounts.saveAndFlush(new CommunityMemberAccount(discordMember, ExternalAccountProvider.DISCORD, "discord-linked", "external"));
        memberships.saveAndFlush(new CommunityUser(second, both, CommunityUserRole.ADMIN));
        var ended = new CommunityUser(third, both, CommunityUserRole.MEMBER); ended.endMembership(now.minusSeconds(60));
        memberships.saveAndFlush(ended);
        var detail = queries.user(both.getId());
        assertThat(detail.user().communityCount()).isEqualTo(2);
        assertThat(detail.communities()).hasSize(3);
        assertThat(detail.communities()).anySatisfy(row -> {
            assertThat(row.communityId()).isEqualTo(first.getId()); assertThat(row.role()).isEqualTo("OWNER");
            assertThat(row.nickname()).isEqualTo("내부 클랜 닉네임"); assertThat(row.status()).isEqualTo("ACTIVE");
        }).anySatisfy(row -> {
            assertThat(row.communityId()).isEqualTo(second.getId()); assertThat(row.role()).isEqualTo("ADMIN");
            assertThat(row.nickname()).isEqualTo("Discord 클랜 닉네임");
        }).anySatisfy(row -> { assertThat(row.communityId()).isEqualTo(third.getId()); assertThat(row.status()).isEqualTo("ENDED"); });
        assertThat(queries.users(both.getId().toString(), "ALL", "createdAt", "desc", 0, 20).content().getFirst().communityCount()).isEqualTo(2);
    }

    @Test void withdrawnMembersStayAnonymizedAndAreExcludedFromActiveCounts() {
        var c = communities.saveAndFlush(new Community("과거 관계"));
        var ended = new CommunityUser(c, withdrawn, CommunityUserRole.MEMBER); ended.endMembership(now);
        memberships.saveAndFlush(ended);
        jdbc.update("update users set last_active_at = ? where id = ?", Timestamp.from(now), withdrawn.getId());
        jdbc.update("update users set last_active_at = ? where id = ?", Timestamp.from(now.minusSeconds(30 * 86400L)), email.getId());
        jdbc.update("update users set last_active_at = ? where id = ?", Timestamp.from(now.minusSeconds(30 * 86400L + 1)), both.getId());
        var statistics = queries.statistics();
        assertThat(statistics.totalUsers()).isEqualTo(6);
        assertThat(statistics.newUsersToday()).isEqualTo(2);
        assertThat(statistics.newUsersLast7Days()).isEqualTo(3);
        assertThat(statistics.normalUsers()).isEqualTo(5);
        assertThat(statistics.activeUsersLast30Days()).isEqualTo(1);
        var detail = queries.user(withdrawn.getId());
        assertThat(detail.user().nickname()).isEqualTo("탈퇴한 사용자");
        assertThat(detail.user().loginMethod()).isEqualTo(com.guildup.developer.dto.DeveloperUserResponses.LoginMethod.NONE);
        assertThat(detail.loginAccounts()).isEmpty(); assertThat(detail.communities().getFirst().status()).isEqualTo("ENDED");
    }

    @Test void allEndpointsRejectAnonymousRegularOwnerAdminAndWithdrawnSystemAdmin() throws Exception {
        var c = communities.saveAndFlush(new Community("권한 테스트"));
        memberships.saveAndFlush(new CommunityUser(c, email, CommunityUserRole.OWNER));
        memberships.saveAndFlush(new CommunityUser(c, discordUser, CommunityUserRole.ADMIN));
        for (String path : List.of("/api/developer/users", "/api/developer/users/statistics", "/api/developer/users/" + both.getId())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (User denied : List.of(email, discordUser, noAccount))
                mvc.perform(get(path).session(session(denied))).andExpect(status().isForbidden());
        }
        jdbc.update("update users set status = 'WITHDRAWN' where id = ?", admin.getId()); em.clear();
        mvc.perform(get("/api/developer/users").session(session(admin))).andExpect(status().isUnauthorized());
    }

    @Test void boundedPaginationSortNullsAndQueryCountHoldForManyUsers() throws Exception {
        for (int i = 0; i < 505; i++) {
            jdbc.update("insert into users(nickname, system_role, status, authentication_version, created_at, updated_at) values (?, 'USER', 'ACTIVE', 0, ?, ?)",
                    "대량회원" + i, Timestamp.from(now.minusSeconds(i)), Timestamp.from(now));
        }
        clearInvocations(namedJdbc);
        var first = queries.users("대량회원", "ALL", "createdAt", "desc", 0, 100);
        var next = queries.users("대량회원", "ALL", "createdAt", "desc", 1, 100);
        assertThat(first.totalElements()).isEqualTo(505); assertThat(first.totalPages()).isEqualTo(6);
        assertThat(first.content()).hasSize(100); assertThat(next.content()).hasSize(100);
        assertThat(first.content().stream().map(row -> row.id()).toList()).doesNotContainAnyElementsOf(next.content().stream().map(row -> row.id()).toList());
        // Exactly one community batch query per page, regardless of page size.
        assertThat(mockingDetails(namedJdbc).getInvocations().stream().filter(call ->
                call.getMethod().getName().equals("query") && call.getMethod().getParameterCount() == 3
                        && call.getMethod().getParameterTypes()[1] == java.util.Map.class).count()).isEqualTo(2);
        jdbc.update("update users set last_login_at = ? where id = ?", Timestamp.from(now), both.getId());
        assertThat(queries.users("", "ALL", "lastLoginAt", "desc", 0, 100).content().getFirst().id()).isEqualTo(both.getId());
        assertThat(queries.users("", "ALL", "lastLoginAt", "asc", 0, 100).content().getFirst().id()).isEqualTo(both.getId());
        assertThat(queries.users("대량회원", "ALL", "createdAt", "asc", 0, 100).content().getFirst().nickname()).isEqualTo("대량회원504");
        mvc.perform(get("/api/developer/dashboard").session(session(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUsers.length()").value(10));
        for (var params : List.of(new String[]{"size", "101"}, new String[]{"page", "-1"}, new String[]{"sort", "id;drop"}, new String[]{"loginMethod", "invalid"}))
            mvc.perform(get("/api/developer/users").param(params[0], params[1]).session(session(admin))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/users/99999999").session(session(admin))).andExpect(status().isNotFound());
    }

    private User user(String nickname, Instant created) {
        var user = users.saveAndFlush(new User(nickname));
        jdbc.update("update users set created_at = ? where id = ?", Timestamp.from(created), user.getId());
        return user;
    }
    private MockHttpSession session(User user) {
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); return session;
    }
}
