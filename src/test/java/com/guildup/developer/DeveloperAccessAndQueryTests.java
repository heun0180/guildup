package com.guildup.developer;

import com.guildup.pubg.model.PubgPlatform;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.BingoEventRepository;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.killcompetition.domain.KillCompetition;
import com.guildup.killcompetition.domain.KillCompetitionGameMode;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:developer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class DeveloperAccessAndQueryTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired DiscordCommunityConnectionRepository discordConnections;
    @Autowired BingoEventRepository bingoEvents;
    @Autowired KillCompetitionRepository killCompetitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    private User systemAdmin;
    private User regularUser;
    private User owner;
    private User communityAdmin;
    private Community community;
    private CommunityGame communityGame;
    private CommunityUser ownerMembership;
    private CommunityMember member;

    @BeforeEach
    void setUp() {
        systemAdmin = users.saveAndFlush(new User("시스템 관리자"));
        regularUser = users.saveAndFlush(new User("일반 사용자"));
        owner = users.saveAndFlush(new User("커뮤니티 소유자"));
        communityAdmin = users.saveAndFlush(new User("커뮤니티 관리자"));
        jdbc.update("update users set system_role = 'SYSTEM_ADMIN' where id = ?", systemAdmin.getId());

        userAccounts.save(new UserExternalAccount(systemAdmin, ExternalAccountProvider.DISCORD, "discord-system", "system"));
        userAccounts.save(new UserExternalAccount(owner, ExternalAccountProvider.DISCORD, "discord-owner", "owner"));
        community = communities.save(new Community("테스트 커뮤니티"));
        communityGame = games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        ownerMembership = communityUsers.save(new CommunityUser(community, owner, CommunityUserRole.OWNER));
        communityUsers.save(new CommunityUser(community, communityAdmin, CommunityUserRole.ADMIN));
        member = members.save(new CommunityMember(community, "PUBG 사용자"));
        memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD,
                "discord-owner", "owner"));
        memberAccounts.save(new CommunityMemberAccount(member, PubgPlatform.KAKAO,
                "account.pubg.test", "PUBG-NICK"));
        discordConnections.save(new DiscordCommunityConnection(community, "guild-123", "테스트 서버"));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void rejectsAnonymousRegularOwnerAndCommunityAdminButAllowsSystemAdmin() throws Exception {
        mvc.perform(get("/developer"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/developer").session(session(regularUser)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/dashboard"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/developer/dashboard").session(session(regularUser)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/dashboard").session(session(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/dashboard").session(session(communityAdmin)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/dashboard").session(session(systemAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.communityCount").value(1))
                .andExpect(jsonPath("$.userCount").value(4));
    }

    @Test
    void exposesSystemAdminFlagAndReadOnlyCommunityData() throws Exception {
        MockHttpSession session = session(systemAdmin);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemAdmin").value(true));

        mvc.perform(get("/api/developer/communities").param("q", "테스트").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].creatorNickname").value("커뮤니티 소유자"))
                .andExpect(jsonPath("$.content[0].discordConnected").value(true));

        mvc.perform(get("/api/developer/communities/{id}", community.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discordGuildId").value("guild-123"));
        mvc.perform(get("/api/developer/communities/{id}/users", community.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].communityRole").value("OWNER"));
        mvc.perform(get("/api/developer/communities/{id}/members", community.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].pubgAccountId").value("account.pubg.test"))
                .andExpect(jsonPath("$.content[0].linkedUserId").value(owner.getId()));
        mvc.perform(get("/api/developer/search").param("q", "PUBG-NICK").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].type").value("PUBG_ACCOUNT"))
                .andExpect(jsonPath("$.results[0].id").value("account.pubg.test"));
    }

    @Test
    void normalSignupRoleRemainsUser() throws Exception {
        mvc.perform(get("/api/auth/me").session(session(regularUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemAdmin").value(false));
    }

    @Test
    void readsBingoAndKillCompetitionDetailsWithoutLoadingUnboundedMatchData() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        BingoEvent bingo = new BingoEvent(community, communityGame, ownerMembership, "운영 빙고", "설명",
                3, 1, false, true, true, false, now.minusSeconds(60), now.plusSeconds(3600),
                BingoStatus.ACTIVE, now);
        bingo.addCell(new BingoCell(bingo, 0, BingoMissionType.KILLS,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.ONE, null, Map.of(), "1킬"));
        bingoEvents.saveAndFlush(bingo);
        KillCompetition kill = killCompetitions.saveAndFlush(new KillCompetition(
                community, communityGame, member, "운영 킬내기", KillCompetitionGameMode.SOLO,
                now.plusSeconds(3600), now));
        entityManager.clear();
        MockHttpSession session = session(systemAdmin);

        mvc.perform(get("/api/developer/communities/{id}/bingos", community.getId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].title").value("운영 빙고"));
        mvc.perform(get("/api/developer/communities/{id}/bingos/{bingoId}", community.getId(), bingo.getId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missions[0].missionType").value("KILLS"));
        mvc.perform(get("/api/developer/communities/{id}/bingos/{bingoId}/processed-matches",
                        community.getId(), bingo.getId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/developer/communities/{id}/kill-competitions", community.getId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].title").value("운영 킬내기"));
        mvc.perform(get("/api/developer/communities/{id}/kill-competitions/{killId}",
                        community.getId(), kill.getId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECRUITING"));
    }

    @Test void platformAccountsDoNotDuplicateMemberRowsOrPaginationAndLegacyNicknameRemainsKakao() throws Exception {
        memberAccounts.saveAndFlush(new CommunityMemberAccount(members.findById(member.getId()).orElseThrow(),
                PubgPlatform.STEAM, "steam-account", "SteamNick"));
        mvc.perform(get("/api/developer/communities/{id}/members?size=1", community.getId()).session(session(systemAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].pubgAccounts.length()").value(2))
                .andExpect(jsonPath("$.content[0].pubgNickname").value("PUBG-NICK"));
        mvc.perform(get("/api/communities/{id}/members", community.getId()).session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].pubgAccounts.length()").value(2))
                .andExpect(jsonPath("$[0].gameNickname").value("PUBG-NICK"));
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }
}
