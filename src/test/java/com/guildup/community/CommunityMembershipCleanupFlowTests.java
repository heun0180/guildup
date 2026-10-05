package com.guildup.community;

import com.guildup.bingo.domain.BingoEvent;
import com.guildup.bingo.domain.BingoStatus;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-membership-cleanup;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@Transactional
class CommunityMembershipCleanupFlowTests {
    @Autowired MockMvc mvc;
    @Autowired CommunityService service;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityGameRepository games;
    @Autowired DiscordCommunityConnectionRepository connections;
    @Autowired UserRepository users;
    @Autowired DiscordOAuthSessionStore oauthResults;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean CommunityDeletionStore deletions;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordApiClient discord;

    @BeforeEach
    void resetDeletionInvocations() {
        clearInvocations(deletions);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void onlyExplicitDiscardDeletesEmptySourceUsingCommonDeletionStore(boolean discard, boolean alreadyJoined)
            throws Exception {
        Fixture fixture = fixture(alreadyJoined);

        join(fixture, discard, discard || !alreadyJoined ? 200 : 409);

        assertThat(count("communities", "id", fixture.source().getId())).isEqualTo(discard ? 0 : 1);
        assertThat(count("community_users", "community_id", fixture.target().getId())).isEqualTo(2);
        assertThat(count("communities", "id", fixture.target().getId())).isEqualTo(1);
        if (discard) {
            verify(deletions).deleteCommunityData(fixture.source().getId());
            assertThat(count("community_users", "community_id", fixture.source().getId())).isZero();
            assertThat(count("community_games", "community_id", fixture.source().getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from community_game_activity_rules where community_game_id = ?",
                    Long.class, fixture.game().getId())).isZero();
        } else {
            verify(deletions, never()).deleteCommunityData(anyLong());
            assertThat(count("community_games", "community_id", fixture.source().getId())).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Content.class)
    void requestedDiscardPreservesSourceWithContentOrUserConfiguration(Content content) throws Exception {
        Fixture fixture = fixture(false);
        addContent(fixture, content);
        entityManager.flush();

        join(fixture, true, 200);

        assertThat(count("communities", "id", fixture.source().getId())).isEqualTo(1);
        assertThat(count("community_users", "community_id", fixture.target().getId())).isEqualTo(2);
        verify(deletions, never()).deleteCommunityData(anyLong());
    }

    @Test
    void existingMembershipWithRequestedDiscardAlsoPreservesNonemptySource() throws Exception {
        Fixture fixture = fixture(true);
        addContent(fixture, Content.POST);
        entityManager.flush();

        join(fixture, true, 200);

        assertThat(count("communities", "id", fixture.source().getId())).isEqualTo(1);
        assertThat(count("community_posts", "community_id", fixture.source().getId())).isEqualTo(1);
        verify(deletions, never()).deleteCommunityData(anyLong());
    }

    @Test
    void falseDiscardPreservesSourceAndItsContent() throws Exception {
        Fixture fixture = fixture(false);
        addContent(fixture, Content.POST);
        entityManager.flush();

        join(fixture, false, 200);

        assertThat(count("communities", "id", fixture.source().getId())).isEqualTo(1);
        assertThat(count("community_posts", "community_id", fixture.source().getId())).isEqualTo(1);
        verify(deletions, never()).deleteCommunityData(anyLong());
    }

    @ParameterizedTest
    @EnumSource(IneligibleSource.class)
    void requestedDiscardStillRequiresNewUnconnectedSourceOwnedBySingleUser(IneligibleSource reason)
            throws Exception {
        Fixture fixture = fixture(false);
        switch (reason) {
            case OLD -> jdbc.update("update communities set created_at = ? where id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(2))), fixture.source().getId());
            case CONNECTED -> connections.save(new DiscordCommunityConnection(fixture.source(), "source-guild", "Source"));
            case SHARED -> memberships.save(new CommunityUser(fixture.source(),
                    users.save(new User("extra")), CommunityUserRole.MEMBER));
            case ADMIN -> jdbc.update("update community_users set role = 'ADMIN' where community_id = ?",
                    fixture.source().getId());
        }
        entityManager.flush();
        entityManager.clear();

        join(fixture, true, 200);

        assertThat(count("communities", "id", fixture.source().getId())).isEqualTo(1);
        verify(deletions, never()).deleteCommunityData(anyLong());
    }

    @Test
    void cleanupIntegrityFailureIsNotMisreportedAsAlreadyJoined() throws Exception {
        Fixture fixture = fixture(false);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("cleanup failed"))
                .when(deletions).deleteCommunityData(fixture.source().getId());

        join(fixture, true, 500);

        verify(deletions).deleteCommunityData(fixture.source().getId());
    }

    private Fixture fixture(boolean alreadyJoined) {
        User joining = users.save(new User("joining"));
        User targetOwner = users.save(new User("target-owner"));
        Community source = service.createCommunity("temporary", joining.getId());
        Community target = service.createCommunity("target", targetOwner.getId());
        connections.saveAndFlush(new DiscordCommunityConnection(target, "target-guild", "Target"));
        if (alreadyJoined) memberships.saveAndFlush(new CommunityUser(target, joining, CommunityUserRole.ADMIN));
        CommunityGame game = games.findByCommunityIdOrderByIdAsc(source.getId()).getFirst();
        return new Fixture(joining, source, target, game);
    }

    private void join(Fixture fixture, boolean discard, int expectedStatus) throws Exception {
        String resultId = oauthResults.saveResult(fixture.source().getId(),
                new DiscordOAuthResultResponse(null, List.of(new DiscordManageableGuildResponse(
                        "target-guild", "Target", null, true))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, fixture.user().getId());
        mvc.perform(post("/api/communities/{communityId}/discord/guild-selection/join", fixture.source().getId())
                        .session(session).contentType("application/json")
                        .content("{\"oauthResultId\":\"" + resultId
                                + "\",\"guildId\":\"target-guild\",\"discardSourceCommunity\":" + discard + "}"))
                .andExpect(status().is(expectedStatus));
    }

    private void addContent(Fixture fixture, Content content) {
        Instant now = Instant.now();
        CommunityUser owner = memberships.findByCommunityIdAndUserId(fixture.source().getId(), fixture.user().getId())
                .orElseThrow();
        switch (content) {
            case MEMBER, LEFT_MEMBER -> {
                CommunityMember member = new CommunityMember(fixture.source(), "manual");
                if (content == Content.LEFT_MEMBER) member.markLeft(now);
                entityManager.persist(member);
            }
            case POST, DELETED_POST -> {
                CommunityPost post = new CommunityPost(fixture.source(), owner, CommunityPostCategory.FREE,
                        "post", "content", false, false, now);
                if (content == Content.DELETED_POST) post.delete(now);
                entityManager.persist(post);
            }
            case NOTICE -> entityManager.persist(new CommunityNotice(fixture.source(), fixture.user(),
                    "notice", "content", false, false, now));
            case EVENT -> entityManager.persist(new CommunityEvent(fixture.source(), fixture.user(),
                    "event", "content", CommunityEventType.GENERAL, now, now.plusSeconds(60), now));
            case BINGO -> entityManager.persist(new BingoEvent(fixture.source(), fixture.game(), owner,
                    "bingo", null, 1, 1, false, false, false, false,
                    now.plusSeconds(60), now.plusSeconds(120), BingoStatus.DRAFT, now));
            case ROLE_SETTING -> entityManager.persist(new CommunityMemberRoleSetting(fixture.source(), "role", "Clan"));
            case NICKNAME_RULE -> entityManager.persist(new CommunityGameNicknameRule(fixture.source(),
                    fixture.game().getGameType(), GameNicknameRuleStrategyType.FULL_NICKNAME,
                    null, null, false, null, "sample", "sample"));
            case CUSTOM_ACTIVITY_RULE -> entityManager.createQuery(
                    "select rule from CommunityGameActivityRule rule where rule.communityGame.id = :gameId",
                    CommunityGameActivityRule.class).setParameter("gameId", fixture.game().getId())
                    .getSingleResult().configure(30, 3);
            case ADDITIONAL_GAME -> entityManager.persist(new CommunityGame(fixture.source(), GameType.BATTLEGROUNDS_STEAM));
            case ACTIVITY_SYNC -> entityManager.persist(new CommunityGameActivitySync(fixture.game()));
            case VOICE -> entityManager.persist(new DiscordVoiceSession(fixture.source(), "old-guild", "user", "channel", "voice", now));
        }
    }

    private long count(String table, String column, Long value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, value);
    }

    private record Fixture(User user, Community source, Community target, CommunityGame game) {}
    private enum Content { MEMBER, LEFT_MEMBER, POST, DELETED_POST, NOTICE, EVENT, BINGO, ROLE_SETTING,
        NICKNAME_RULE, CUSTOM_ACTIVITY_RULE, ADDITIONAL_GAME, ACTIVITY_SYNC, VOICE }
    private enum IneligibleSource { OLD, CONNECTED, SHARED, ADMIN }
}
