package com.guildup.feedback;

import com.guildup.feedback.domain.Feedback;
import com.guildup.feedback.domain.FeedbackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** 반드시 격리된 테스트 DB만 TEST_POSTGRES_URL로 지정한다. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class FeedbackPostgresTests extends FeedbackFlowTests {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void migrationIsRepeatablePreservesExistingCommunityContextAndAllowsNull() throws Exception {
        var now = Instant.parse("2026-09-14T18:30:00Z");
        var user = users.findAll().getFirst();
        var community = communities.findAll().getFirst();
        var existing = feedbacks.saveAndFlush(new Feedback(user, FeedbackType.FEATURE, "기존 문의", "기존 내용",
                community.getId(), community.getName(), "/members.html", now));
        jdbc.execute("alter table feedbacks alter column community_id set not null");
        String sql = new ClassPathResource("db/manual/add_global_feedback.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);
        var retained = feedbacks.findAll().getFirst();
        assertThat(retained.getId()).isEqualTo(existing.getId());
        assertThat(retained.getCommunityId()).isEqualTo(community.getId());
        assertThat(retained.getCommunityName()).isEqualTo(community.getName());
        assertThat(retained.getContent()).isEqualTo("기존 내용");
        feedbacks.saveAndFlush(new Feedback(user, FeedbackType.SERVICE, "공용 문의", "내용", null, null, "/account.html", now));
        assertThat(feedbacks.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'feedbacks' and column_name = 'community_id'", String.class)).isEqualTo("YES");
    }
}
