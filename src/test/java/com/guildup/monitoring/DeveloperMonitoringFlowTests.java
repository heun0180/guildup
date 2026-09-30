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

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserSession.USER_ID, user.getId());
        return session;
    }
}
