package com.guildup.monitoring;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.monitoring.service.RealtimeLogStreamService;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:live-log-access;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
class RealtimeLogAccessTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired RealtimeLogStreamService streams;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @Test void serverProtectsStreamAndClosesSubscriptionOnLogout() throws Exception {
        var regular = users.saveAndFlush(new User("live-regular"));
        var admin = users.saveAndFlush(new User("live-admin"));
        jdbc.update("update users set system_role='SYSTEM_ADMIN' where id=?", admin.getId());
        String endpoint = "/api/developer/monitoring/logs/stream";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).session(session(regular))).andExpect(status().isForbidden());
        // Community OWNER/ADMIN privileges are not consulted by this endpoint.
        var adminSession = session(admin);
        var result = mvc.perform(get(endpoint).session(adminSession))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        assertThat(streams.connectionCount()).isEqualTo(1);
        adminSession.invalidate();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (streams.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(streams.connectionCount()).isZero();
        assertThat(result.getResponse().getContentAsString()).contains("event:ready");
    }
    private MockHttpSession session(User user) {
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); return session;
    }
}
