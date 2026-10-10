package com.guildup.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.web.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.guildup.monitoring.domain.*;

class GlobalExceptionLoggingTests {
    @RestController static class BrokenController {
        enum Game { STEAM }
        record Body(Game game) {}
        @PostMapping("/api/input") String input(@RequestBody Body body) { return "ok"; }
        @GetMapping("/api/communities/12/broken") String broken() { throw new IllegalStateException("password=never-return"); }
        @GetMapping("/api/database-broken") String database() { throw new DataIntegrityViolationException("SQL [select private from users]"); }
        @GetMapping("/api/already-logged") String logged() {
            var failure = new IllegalStateException("test failure");
            LoggerFactory.getLogger(BrokenController.class).error("Feature failed", failure);
            FailureLogContext.markLogged(failure);
            throw failure;
        }
    }

    @Test void terminalFailureLogsTraceContextAndEventsOnceAndHidesInternalDetails() throws Exception {
        var monitoring = mock(MonitoringEventService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BrokenController()).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestLogContextFilter(), new HttpServerErrorMonitoringFilter(monitoring)).build();
        Logger logger = (Logger) LoggerFactory.getLogger(HttpServerErrorMonitoringFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var result = mvc.perform(get("/api/communities/12/broken").header("X-Request-ID", "request-1234"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."))
                    .andReturn();
            String requestId = result.getResponse().getHeader("X-Request-ID");
            assertThat(requestId).matches("[a-f0-9-]{36}").isNotEqualTo("request-1234");
            assertThat(result.getResponse().getContentAsString()).contains(requestId);
            assertThat(appender.list).hasSize(1);
            var logged = appender.list.getFirst();
            assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
            assertThat(logged.getThrowableProxy()).isNotNull();
            assertThat(logged.getMDCPropertyMap()).containsEntry("requestId", requestId);
            verify(monitoring).recordError(eq(MonitoringCategory.HTTP), eq(MonitoringEventCode.HTTP_5XX), anyString(), eq(12L), isNull(), anyString(), argThat(m -> requestId.equals(m.get("requestId"))));
            assertThat(MDC.get("requestId")).isNull();
            appender.list.clear();
            mvc.perform(post("/api/input").contentType("application/json").content("{\"game\":\"UNKNOWN\"}"))
                    .andExpect(status().isBadRequest());
            assertThat(appender.list).isEmpty();
            mvc.perform(get("/api/already-logged")).andExpect(status().isInternalServerError());
            assertThat(appender.list).isEmpty();
            mvc.perform(get("/api/database-broken")).andExpect(status().isInternalServerError());
            verify(monitoring).recordError(eq(MonitoringCategory.DATABASE), eq(MonitoringEventCode.DATABASE_ERROR), anyString(), isNull(), isNull(), anyString(), anyMap());
        } finally { logger.detachAppender(appender); }
    }

    @Test void expiredSessionAndMonitoringFailureCannotReplaceOriginalBusinessException() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/communities/12/broken");
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(session.getAttribute(com.guildup.user.auth.service.CurrentUserSession.USER_ID))
                .thenThrow(new IllegalStateException("session invalidated concurrently"));
        request.setSession(session);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var monitoring = mock(MonitoringEventService.class);
        doThrow(new IllegalStateException("monitoring unavailable")).when(monitoring).recordError(
                any(), any(), anyString(), any(), any(), anyString(), anyMap());
        var original = new IllegalArgumentException("original feature failure");
        jakarta.servlet.FilterChain failing = (req, res) -> { throw original; };
        var filter = new HttpServerErrorMonitoringFilter(monitoring);
        assertThatThrownBy(() -> new RequestLogContextFilter().doFilter(request, response,
                (req, res) -> filter.doFilter(req, res, failing))).isSameAs(original);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MDC.get("requestId")).isNull();
    }
}
