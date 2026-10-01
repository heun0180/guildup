package com.guildup.community;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberScore;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.RankingPeriodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 동일 기간/권한/중복 지급 흐름을 PostgreSQL에서도 검증하며 운영 DDL 보존을 추가 확인한다. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
class CommunityRankingPostgresIntegrationTests extends CommunityScoreFlowTests {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void manualMigrationDefaultsExistingCommunityToAllTimeAndPreservesTotalsAndIsRepeatable() throws Exception {
        jdbc.execute("alter table communities drop column ranking_period_type");
        jdbc.execute("drop index idx_community_score_history_community_created");
        Long communityId = jdbc.queryForObject(
                "insert into communities(name, created_at) values ('기존 커뮤니티', current_timestamp) returning id", Long.class);
        String migration = new ClassPathResource("db/manual/add_community_ranking_periods.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migration);
        jdbc.execute(migration);
        var community = communities.findById(communityId).orElseThrow();
        assertThat(community.getRankingPeriodType()).isEqualTo(RankingPeriodType.ALL_TIME);
        communityUsers.save(new CommunityUser(community, user, CommunityUserRole.OWNER));
        var member = members.save(new CommunityMember(community, "기존 클랜원"));
        var score = new CommunityMemberScore(member, clock.instant());
        score.add(27, clock.instant());
        scores.save(score);
        jdbc.execute(migration);
        assertThat(rankingService.getRankings(user.getId(), communityId).rankings().getFirst().score()).isEqualTo(27);
        assertThat(scores.findByCommunityMemberId(member.getId()).orElseThrow().getTotalScore()).isEqualTo(27);
    }
}
