package com.guildup.killcompetition;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.killcompetition.domain.KillCompetitionGameMode;
import com.guildup.killcompetition.dto.KillCompetitionCreateRequest;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.killcompetition.service.*;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL timestamptz의 마이크로초 정밀도를 거치는 실제 claim/finish 트랜잭션 검증. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@Import(KillCompetitionPostgresClaimIntegrationTests.ClockConfig.class)
class KillCompetitionPostgresClaimIntegrationTests {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired KillCompetitionService competitions;
    @Autowired KillCompetitionParticipationService participation;
    @Autowired KillCompetitionSettlementService settlements;
    @Autowired KillCompetitionSettlementStore settlementStore;
    @Autowired KillCompetitionRepository competitionRepository;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository communityGames;
    @Autowired CommunityUserRepository communityUsers;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userAccounts;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;
    @MockitoBean KillCompetitionPubgAggregator aggregator;
    @MockitoBean PubgPlayerService pubgPlayers;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    @Test
    void completesWithUuidClaimAfterPostgresRoundsClaimTimestamp() {
        clock.set(Instant.parse("2026-09-15T12:00:00.123456789Z"));
        Community community = communities.save(new Community("PostgreSQL claim test"));
        CommunityGame game = communityGames.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        User user = users.save(new User("creator"));
        userAccounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "discord-creator", "creator"));
        communityUsers.save(new CommunityUser(community, user, CommunityUserRole.MEMBER));
        CommunityMember member = members.save(new CommunityMember(community, "creator"));
        memberAccounts.save(new CommunityMemberAccount(
                member, ExternalAccountProvider.DISCORD, "discord-creator", "creator"));
        memberAccounts.save(new CommunityMemberAccount(
                member, ExternalAccountProvider.PUBG, "account-creator", "pubg-creator"));

        var created = competitions.create(user.getId(), community.getId(), game.getId(),
                new KillCompetitionCreateRequest("precision", KillCompetitionGameMode.SOLO,
                        clock.instant().plus(Duration.ofMinutes(30))));
        participation.join(user.getId(), community.getId(), created.id());
        competitions.closeRecruitment(user.getId(), community.getId(), created.id());
        var started = competitions.start(user.getId(), community.getId(), created.id());
        clock.set(started.endsAt().plusSeconds(1));
        var pending = settlements.finalizeResult(user.getId(), community.getId(), started.id());
        // DB에서 읽은 publish 시각에 다시 나노초를 더해 Java/PG 정밀도 차이를 의도적으로 만든다.
        clock.set(pending.resultPublishAt().plusNanos(789));

        var work = settlementStore.claimDueFinal(started.id());
        Instant storedClaimTime = jdbcTemplate.queryForObject(
                "select finalization_started_at from kill_competitions where id = ?",
                (result, row) -> result.getObject(1, OffsetDateTime.class).toInstant(), started.id());
        assertThat(storedClaimTime).isNotEqualTo(work.claimAt());
        assertThat(storedClaimTime.getNano() % 1_000).isZero();

        settlementStore.finishFinal(work.communityId(), work,
                new KillCompetitionKillSnapshot(Map.of(), List.of()));

        assertThat(competitionRepository.findById(started.id())).get().satisfies(completed -> {
            assertThat(completed.getStatus().name()).isEqualTo("COMPLETED");
            assertThat(completed.getCompletedAt()).isNotNull();
            assertThat(completed.getFinalizationStartedAt()).isNull();
            assertThat(completed.getFinalizationClaimToken()).isNull();
        });
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
