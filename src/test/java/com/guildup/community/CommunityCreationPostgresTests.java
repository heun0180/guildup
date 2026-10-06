package com.guildup.community;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL의 행 잠금, UNIQUE 경쟁과 배포 SQL을 격리 DB에서 검증한다. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"})
class CommunityCreationPostgresTests extends CommunityFlowTests {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void deploymentSqlIsRepeatableAndExistingCommunitiesCanReceiveInvitations() throws Exception {
        var community = service.createCommunity("기존 커뮤니티", user.getId());
        jdbc.update("update communities set invite_code = null where id = ?", community.getId());
        String sql = new ClassPathResource("db/manual/add_community_creation_safety.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);
        assertThat(communities.count()).isEqualTo(1);
        assertThat(service.getInvitation(user.getId(), community.getId())).isNotBlank();
        assertThat(connections.count()).isZero();
        assertThat(communityMembers.count()).isEqualTo(1);
        assertThat(memberships.findByCommunityIdAndUserId(community.getId(), user.getId()).orElseThrow()
                .getCommunityMember()).isNotNull();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/communities/" + community.getId() + "/attendance").session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
