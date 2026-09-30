package com.guildup.monitoring;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.service.MonitoringRetentionScheduler;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "monitoring.retention-days=30"
})
class MonitoringPersistenceAndRetentionTests {
    @Autowired MonitoringEventService monitoring;
    @Autowired MonitoringEventRepository events;
    @Autowired MonitoringRetentionScheduler retention;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    @BeforeEach
    void clear() { events.deleteAll(); }

    @Test
    void persistsOnlySanitizedOperationalContext() {
        monitoring.recordError(MonitoringCategory.BINGO, MonitoringEventCode.BINGO_AGGREGATION_FAILED,
                "Bingo aggregation failed", 12L, 7L, "bingoEventId=31", Map.of(
                        "bingoEventId", 31L, "matchId", "match-1", "refreshToken", "never-store-me"));

        MonitoringEvent saved = events.findAll().getFirst();
        assertThat(saved.getSeverity()).isEqualTo(MonitoringSeverity.ERROR);
        assertThat(saved.getMetadata()).containsEntry("matchId", "match-1")
                .doesNotContainKey("refreshToken");
    }

    @Test
    void deletesOnlyEventsOlderThanRetentionPeriod() {
        Instant now = Instant.now();
        events.save(new MonitoringEvent(MonitoringSeverity.WARN, MonitoringCategory.SYSTEM,
                MonitoringEventCode.UNEXPECTED_EXCEPTION, "old", null, null, "old", Map.of(),
                now.minus(31, ChronoUnit.DAYS)));
        events.save(new MonitoringEvent(MonitoringSeverity.WARN, MonitoringCategory.SYSTEM,
                MonitoringEventCode.UNEXPECTED_EXCEPTION, "recent", null, null, "recent", Map.of(),
                now.minus(29, ChronoUnit.DAYS)));

        retention.deleteExpiredEvents();

        assertThat(events.findAll()).extracting(MonitoringEvent::getMessage).containsExactly("recent");
    }
}
