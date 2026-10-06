package com.guildup.community;

import com.guildup.pubg.model.PubgPlatform;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.*;
import com.guildup.pubg.domain.*;
import com.guildup.pubg.repository.*;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-deletion;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@com.guildup.support.SessionCsrfTestClient.WithSessionCsrf
@Transactional
class CommunityDeletionFlowTests {
    @Autowired MockMvc mvc;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityGameRepository communityGames;
    @Autowired CommunityMemberRepository communityMembers;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityMemberRoleSettingRepository roleSettings;
    @Autowired DiscordCommunityConnectionRepository discordConnections;
    @Autowired CommunityPostRepository posts;
    @Autowired CommunityPostCommentRepository comments;
    @Autowired BingoEventRepository bingoEvents;
    @Autowired BingoParticipantRepository bingoParticipants;
    @Autowired BingoProgressRepository bingoProgress;
    @Autowired BingoProcessedMatchRepository bingoProcessedMatches;
    @Autowired BingoProcessedSourceRepository bingoProcessedSources;
    @Autowired BingoLineCompletionRepository bingoLines;
    @Autowired KillCompetitionRepository killCompetitions;
    @Autowired KillCompetitionTeamRepository killTeams;
    @Autowired KillCompetitionParticipantRepository killParticipants;
    @Autowired KillCompetitionMatchResultRepository killResults;
    @Autowired PubgStoredMatchRepository pubgMatches;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;
    @MockitoBean DiscordApiClient discordApiClient;
    @MockitoBean com.guildup.pubg.service.PubgPlayerService pubgPlayerService;

    @Test
    void ownerDeletesOnlyTheirCommunityGraphAndPreservesSharedPubgFacts() throws Exception {
        User owner = users.save(new User("owner"));
        User otherOwner = users.save(new User("other-owner"));
        Community target = communities.createCommunity("삭제 대상", owner.getId());
        Community untouched = communities.createCommunity("유지 대상", otherOwner.getId());
        createCommunityGraph(target, owner, "target");
        createBasicCommunityData(untouched, "untouched");
        createSharedPubgFacts();

        assertThat(count("pubg_bingo_events")).isEqualTo(1);
        assertThat(count("pubg_kill_competitions")).isEqualTo(1);
        assertThat(count("community_posts")).isEqualTo(1);
        clearInvocations(jda, discordBot, discordApiClient);

        mvc.perform(delete("/api/communities/{communityId}", target.getId()).session(session(owner)))
                .andExpect(status().isNoContent());

        assertThat(countWhere("communities", "id", target.getId())).isZero();
        assertThat(countWhere("communities", "id", untouched.getId())).isEqualTo(1);
        assertThat(countWhere("community_users", "community_id", target.getId())).isZero();
        assertThat(countWhere("community_users", "community_id", untouched.getId())).isEqualTo(1);
        assertThat(countWhere("community_members", "community_id", target.getId())).isZero();
        assertThat(countWhere("community_members", "community_id", untouched.getId())).isEqualTo(1);
        assertThat(countWhere("discord_community_connections", "community_id", target.getId())).isZero();
        assertThat(countWhere("discord_community_connections", "community_id", untouched.getId())).isEqualTo(1);

        for (String table : new String[]{
                "pubg_bingo_events", "pubg_bingo_cells", "pubg_bingo_participants",
                "pubg_bingo_progress", "pubg_bingo_processed_matches", "pubg_bingo_processed_sources",
                "pubg_bingo_line_completions", "pubg_kill_competitions",
                "pubg_kill_competition_teams", "pubg_kill_competition_participants",
                "pubg_kill_competition_match_results", "community_posts", "community_post_comments"
        }) {
            assertThat(count(table)).as(table).isZero();
        }

        assertThat(count("pubg_matches")).isEqualTo(1);
        assertThat(count("pubg_match_players")).isEqualTo(1);
        assertThat(count("pubg_match_kills")).isEqualTo(1);
        verifyNoInteractions(jda, discordBot, discordApiClient);
    }

    @Test
    void adminAndMemberCannotDeleteCommunity() throws Exception {
        User owner = users.save(new User("owner"));
        User admin = users.save(new User("admin"));
        User member = users.save(new User("member"));
        Community community = communities.createCommunity("권한 테스트", owner.getId());
        communityUsers.save(new CommunityUser(community, admin, CommunityUserRole.ADMIN));
        communityUsers.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));

        mvc.perform(delete("/api/communities/{communityId}", community.getId()).session(session(admin)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/communities/{communityId}", community.getId()).session(session(member)))
                .andExpect(status().isForbidden());

        assertThat(countWhere("communities", "id", community.getId())).isEqualTo(1);
    }

    @Test
    void missingCommunityReturnsNotFoundAndDeletedCommunityReturnsNotFoundAgain() throws Exception {
        User owner = users.save(new User("owner"));

        mvc.perform(delete("/api/communities/{communityId}", 999_999L).session(session(owner)))
                .andExpect(status().isNotFound());

        Community community = communities.createCommunity("한 번만 삭제", owner.getId());
        mvc.perform(delete("/api/communities/{communityId}", community.getId()).session(session(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/communities/{communityId}", community.getId()).session(session(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletionRequiresLogin() throws Exception {
        mvc.perform(delete("/api/communities/{communityId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    private void createCommunityGraph(Community community, User owner, String suffix) {
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        CommunityUser communityUser = communityUsers.findByCommunityIdAndUserId(community.getId(), owner.getId())
                .orElseThrow();
        CommunityGame game = communityGames.findByCommunityIdOrderByIdAsc(community.getId()).getFirst();
        CommunityMember member = communityMembers.save(new CommunityMember(community, "member-" + suffix));
        memberAccounts.save(new CommunityMemberAccount(
                member, PubgPlatform.KAKAO, "account-" + suffix, "player-" + suffix));
        roleSettings.save(new CommunityMemberRoleSetting(community, "role-" + suffix, "Clan"));
        discordConnections.save(new DiscordCommunityConnection(community, "guild-" + suffix, "Guild"));

        CommunityPost post = posts.save(new CommunityPost(
                community, communityUser, CommunityPostCategory.FREE, "제목", "내용", false, false, now));
        comments.save(new CommunityPostComment(post, communityUser, "댓글", now));

        BingoEvent bingo = new BingoEvent(community, game, communityUser, "빙고", null,
                1, 1, false, false, false, false,
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), BingoStatus.DRAFT, now);
        BingoCell cell = new BingoCell(bingo, 0, BingoMissionType.KILLS,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.ONE, null, Map.of(), null);
        bingo.addCell(cell);
        bingoEvents.saveAndFlush(bingo);
        BingoParticipant participant = bingoParticipants.saveAndFlush(new BingoParticipant(
                bingo, communityUser, member, "account-" + suffix, "player-" + suffix, now, now));
        bingoProgress.save(new BingoProgress(participant, cell, now));
        bingoProcessedMatches.save(new BingoProcessedMatch(bingo, participant, "match-" + suffix, now, now));
        bingoProcessedSources.save(new BingoProcessedSource(
                bingo, participant, BingoProgressSourceType.PUBG_MATCH, "match-" + suffix, now, now));
        bingoLines.save(new BingoLineCompletion(participant, "ROW_0", now));

        KillCompetition competition = killCompetitions.saveAndFlush(new KillCompetition(
                community, game, member, "킬내기", KillCompetitionGameMode.SOLO, now.plus(Duration.ofHours(2)), now));
        KillCompetitionTeam team = killTeams.saveAndFlush(new KillCompetitionTeam(competition, "1팀", 1));
        KillCompetitionParticipant killParticipant = new KillCompetitionParticipant(
                competition, member, "account-" + suffix, "player-" + suffix);
        killParticipant.assignTeam(team);
        killParticipants.saveAndFlush(killParticipant);
        killResults.save(new KillCompetitionMatchResult(
                competition, killParticipant, "match-" + suffix, now, 3, 1, 3, 5, 8));
    }

    private void createBasicCommunityData(Community community, String suffix) {
        communityMembers.save(new CommunityMember(community, "member-" + suffix));
        discordConnections.save(new DiscordCommunityConnection(community, "guild-" + suffix, "Guild"));
    }

    private void createSharedPubgFacts() {
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        PubgStoredMatch match = new PubgStoredMatch(
                "shared-match", "kakao", now, "squad", "official", "Erangel", false, "telemetry", 1200, now);
        match.addPlayer(new PubgStoredMatchPlayer(match, "shared-account", "Player", 1,
                1, BigDecimal.TEN, 0, 0, 0, 0, 0, 0,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 1, now));
        match.replaceTelemetry(java.util.List.of(new PubgStoredMatchKill(
                match, "shared-account", "victim", "M416", "AR", null,
                10.0, false, false, now, now)), now);
        pubgMatches.saveAndFlush(match);
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long countWhere(String table, String column, Long value) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, value);
    }
}
