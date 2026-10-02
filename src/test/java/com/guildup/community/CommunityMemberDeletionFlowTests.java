package com.guildup.community;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.activity.ClanActivityStatus;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberActivitySnapshot;
import com.guildup.community.domain.CommunityMemberScore;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberActivitySnapshotRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityMemberScoreRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-member-deletion;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@Transactional
class CommunityMemberDeletionFlowTests {
    @Autowired MockMvc mvc;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityGameRepository games;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository accounts;
    @Autowired CommunityMemberScoreRepository scores;
    @Autowired CommunityMemberActivitySnapshotRepository snapshots;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;
    @MockitoBean DiscordApiClient discordApiClient;
    @MockitoBean PubgPlayerService pubgPlayerService;

    User owner;
    User admin;
    User ordinaryUser;
    User outsider;
    Community community;
    CommunityMember member;

    @BeforeEach
    void setUp() {
        owner = users.save(new User("owner"));
        admin = users.save(new User("admin"));
        ordinaryUser = users.save(new User("member"));
        outsider = users.save(new User("outsider"));
        community = communities.createCommunity("수동 클랜원 테스트", owner.getId());
        memberships.save(new CommunityUser(community, admin, CommunityUserRole.ADMIN));
        memberships.save(new CommunityUser(community, ordinaryUser, CommunityUserRole.MEMBER));
        member = members.save(new CommunityMember(community, "수동 클랜원"));
    }

    @Test
    void ownerRemovesManualMemberFromListWhilePreservingGameAccountAndHistory() throws Exception {
        CommunityMember remaining = members.save(new CommunityMember(community, "유지할 클랜원"));
        CommunityMemberAccount gameAccount = accounts.save(new CommunityMemberAccount(
                member, ExternalAccountProvider.PUBG, "account.manual", "ManualPlayer"));
        Instant now = Instant.now();
        CommunityMemberScore score = new CommunityMemberScore(member, now);
        score.add(42, now);
        scores.save(score);
        var game = games.findByCommunityIdOrderByIdAsc(community.getId()).getFirst();
        CommunityMemberActivitySnapshot snapshot = snapshots.save(new CommunityMemberActivitySnapshot(
                game, member, "account.manual", "ManualPlayer", ClanActivityStatus.ACTIVE, now, now, now));

        mvc.perform(delete(memberPath()).session(session(owner))).andExpect(status().isNoContent());
        flushAndClear();

        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(CommunityMemberStatus.LEFT);
        assertThat(accounts.existsById(gameAccount.getId())).isTrue();
        assertThat(scores.findByCommunityMemberId(member.getId()).orElseThrow().getTotalScore()).isEqualTo(42);
        assertThat(snapshots.existsById(snapshot.getId())).isTrue();
        mvc.perform(get(membersPath()).session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(remaining.getId()));
    }

    @Test
    void adminCanDeleteManualMemberAndRepeatedDeletionKeepsTimestamp() throws Exception {
        mvc.perform(delete(memberPath()).session(session(admin))).andExpect(status().isNoContent());
        flushAndClear();
        CommunityMember deleted = members.findById(member.getId()).orElseThrow();
        Instant deletedAt = deleted.getUpdatedAt();
        assertThat(deleted.getStatus()).isEqualTo(CommunityMemberStatus.LEFT);

        mvc.perform(delete(memberPath()).session(session(admin))).andExpect(status().isNoContent());
        flushAndClear();
        assertThat(members.findById(member.getId()).orElseThrow().getUpdatedAt()).isEqualTo(deletedAt);
        mvc.perform(get(membersPath()).session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void ordinaryMemberCannotDelete() throws Exception {
        mvc.perform(delete(memberPath()).session(session(ordinaryUser))).andExpect(status().isForbidden());
        assertThat(member.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    @Test
    void outsiderCannotDelete() throws Exception {
        mvc.perform(delete(memberPath()).session(session(outsider))).andExpect(status().isForbidden());
        assertThat(member.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    @Test
    void deletionRequiresLogin() throws Exception {
        mvc.perform(delete(memberPath())).andExpect(status().isUnauthorized());
        assertThat(member.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    @Test
    void cannotDeleteMemberThroughAnotherCommunityEvenWhenManagingBoth() throws Exception {
        Community other = communities.createCommunity("다른 클랜", owner.getId());
        mvc.perform(delete("/api/communities/{communityId}/members/{memberId}", other.getId(), member.getId())
                        .session(session(owner)))
                .andExpect(status().isNotFound());
        assertThat(member.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    @Test
    void missingMemberReturnsNotFound() throws Exception {
        mvc.perform(delete(membersPath() + "/" + Long.MAX_VALUE).session(session(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void discordLinkedMemberCannotBeDeletedEvenWithoutDiscordUsername() throws Exception {
        accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "123456", null));
        mvc.perform(delete(memberPath()).session(session(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "수동 등록한 클랜원만 삭제할 수 있습니다. Discord 클랜원은 역할 설정으로 관리해 주세요."));
        assertThat(member.getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
    }

    private String membersPath() {
        return "/api/communities/" + community.getId() + "/members";
    }

    private String memberPath() {
        return membersPath() + "/" + member.getId();
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
