package com.guildup.monitoring;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
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

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
class DeveloperMonitoringFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MonitoringEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    private User admin;
    private User regular;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        users.deleteAll();
        admin = users.saveAndFlush(new User("monitor-admin"));
        regular = users.saveAndFlush(new User("monitor-user"));
        jdbc.update("update users set system_role = 'SYSTEM_ADMIN' where id = ?", admin.getId());
        events.save(new MonitoringEvent(MonitoringSeverity.ERROR, MonitoringCategory.HTTP,
                MonitoringEventCode.HTTP_5XX, "POST /api/example returned 500", 12L, admin.getId(),
                "POST /api/example", Map.of("status", 500, "elapsedMs", 40), Instant.now()));
        events.save(new MonitoringEvent(MonitoringSeverity.WARN, MonitoringCategory.PUBG_API,
                MonitoringEventCode.PUBG_API_RATE_LIMIT, "PUBG API rate limit reached", null, null,
                "MATCH", Map.of("status", 429), Instant.now()));
    }

    @Test
    void allowsOnlySystemAdminAndReturnsSummary() throws Exception {
        mvc.perform(get("/api/developer/monitoring/summary").session(session(regular)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/monitoring/summary").session(session(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.last24Hours.errors").value(1))
                .andExpect(jsonPath("$.last24Hours.http5xx").value(1))
                .andExpect(jsonPath("$.last24Hours.pubg429").value(1));
    }

    @Test
    void filtersPaginatesAndReturnsEventDetail() throws Exception {
        MockHttpSession session = session(admin);
        mvc.perform(get("/api/developer/monitoring/events")
                        .param("severity", "ERROR").param("category", "HTTP")
                        .param("eventCode", "HTTP_5XX").param("communityId", "12")
                        .param("page", "0").param("size", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventCode").value("HTTP_5XX"))
                .andExpect(jsonPath("$.content[0].metadata.status").value(500));

        Long id = events.findAll().stream().filter(event -> event.getEventCode() == MonitoringEventCode.HTTP_5XX)
                .findFirst().orElseThrow().getId();
        mvc.perform(get("/api/developer/monitoring/events/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.referenceId").value("POST /api/example"));
    }

    @Test
    void activityFiltersIncludeSuccessfulSlowSyncsAndReturnChronologicalCorrelatedPubgEvents() throws Exception {
        String syncId = "12345678-1234-1234-1234-123456789abc";
        Instant start = Instant.parse("2026-10-07T01:00:00Z");
        events.save(new MonitoringEvent(MonitoringSeverity.INFO, MonitoringCategory.SYSTEM,
                MonitoringEventCode.ACTIVITY_SYNC_STARTED, "시작", 12L, admin.getId(), syncId,
                Map.of("syncId", syncId, "gameType", "BATTLEGROUNDS_KAKAO", "communityGameId", 31), start));
        events.save(new MonitoringEvent(MonitoringSeverity.WARN, MonitoringCategory.PUBG_API,
                MonitoringEventCode.PUBG_API_RATE_LIMIT, "PUBG_RATE_LIMIT", 12L, admin.getId(), syncId,
                Map.of("syncId", syncId, "status", 429), start.plusSeconds(1)));
        var completed = events.save(new MonitoringEvent(MonitoringSeverity.INFO, MonitoringCategory.SYSTEM,
                MonitoringEventCode.ACTIVITY_SYNC_COMPLETED, "완료", 12L, admin.getId(), syncId,
                Map.of("syncId", syncId, "gameType", "BATTLEGROUNDS_KAKAO", "durationMs", 168500,
                        "syncStatus", "SUCCESS", "snapshotCount", 78), start.plusSeconds(168)));
        String failedId = "22345678-1234-1234-1234-123456789abc";
        var failed = events.save(new MonitoringEvent(MonitoringSeverity.ERROR, MonitoringCategory.SYSTEM,
                MonitoringEventCode.ACTIVITY_SYNC_FAILED, "실패", 13L, admin.getId(), failedId,
                Map.of("syncId", failedId, "gameType", "BATTLEGROUNDS_STEAM", "durationMs", 72000,
                        "syncStatus", "FAILED", "failureStage", "MATCH_FETCH", "stackTrace", "safe trace"), start.plusSeconds(72)));

        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("group", "ACTIVITY").param("severity", "INFO").param("communityId", "12")
                        .param("gameType", "BATTLEGROUNDS_KAKAO").param("syncStatus", "SUCCESS")
                        .param("eventCode", "ACTIVITY_SYNC_COMPLETED").param("minDurationMs", "60000")
                        .param("from", start.toString()).param("to", start.plusSeconds(180).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(completed.getId()))
                .andExpect(jsonPath("$.content[0].metadata.durationMs").value(168500));
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("syncId", syncId).param("order", "ASC").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].eventCode").value("PUBG_API_RATE_LIMIT"));
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("group", "PUBG_API").param("syncId", syncId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("group", "ACTIVITY").param("syncStatus", "FAILED").param("gameType", "BATTLEGROUNDS_STEAM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].metadata.stackTrace").doesNotExist());
        mvc.perform(get("/api/developer/monitoring/events/{id}", failed.getId()).session(session(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.failureStage").value("MATCH_FETCH"))
                .andExpect(jsonPath("$.metadata.stackTrace").value("safe trace"));
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("group", "ACTIVITY").param("minDurationMs", "180000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void activityLogEndpointsAndPageKeepExistingAdministratorAccessRules() throws Exception {
        Long id = events.findAll().getFirst().getId();
        for (String path : java.util.List.of("/api/developer/monitoring/events?group=ACTIVITY",
                "/api/developer/monitoring/events?syncId=12345678-1234-1234-1234-123456789abc&order=ASC",
                "/api/developer/monitoring/events/" + id, "/developer/monitoring")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).session(session(regular))).andExpect(status().isForbidden());
        }
    }

    @Test
    void rejectsInvalidActivityFiltersAndCapsPageSize() throws Exception {
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin)).param("syncId", "bad-id"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin)).param("minDurationMs", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin))
                        .param("from", "2026-10-08T00:00:00Z").param("to", "2026-10-07T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/monitoring/events").session(session(admin)).param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }
}
