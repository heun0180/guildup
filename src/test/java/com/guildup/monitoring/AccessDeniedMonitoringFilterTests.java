package com.guildup.monitoring;

import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.web.AccessDeniedMonitoringFilter;
import com.guildup.monitoring.web.RequestLogContextFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessDeniedMonitoringFilterTests {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void rejectionUsesMappedRouteAndServerIdWithoutRequestSecrets() throws Exception {
        var monitoring = mock(MonitoringEventService.class);
        var filter = new AccessDeniedMonitoringFilter(monitoring, new TestClock(), 30);
        var request = new MockHttpServletRequest("POST", "/api/auth/login;jsessionid=private-session");
        request.setQueryString("code=private-code&state=private-state");
        request.addHeader("Cookie", "JSESSIONID=private-cookie");
        request.addHeader("Authorization", "Bearer private-bearer");
        request.addHeader("X-CSRF-Token", "private-security-value");
        request.addHeader("X-Request-ID", "private-request-header");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/auth/login");
        AccessDeniedMonitoringFilter.mark(request, AccessDeniedMonitoringFilter.Denial.CSRF_SESSION_MISSING);
        var response = new MockHttpServletResponse();
        new RequestLogContextFilter().doFilter(request, response, (req, res) ->
                filter.doFilter(req, res, (ignored, denied) -> response.setStatus(403)));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(monitoring).recordWarn(eq(MonitoringCategory.SECURITY), eq(MonitoringEventCode.HTTP_ACCESS_DENIED),
                anyString(), isNull(), isNull(), eq(response.getHeader("X-Request-ID")), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("endpoint", "/api/auth/login")
                .containsEntry("status", 403).containsEntry("reason", "SESSION_MISSING")
                .containsEntry("rule", "SESSION_CSRF").containsEntry("layer", "SESSION_CSRF");
        assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}");
        assertThat(metadata.getValue().toString()).doesNotContain("private-", "code=", "state=", "jsessionid=");
    }

    @Test void monitoringFailureCannotReplaceACommitted403Body() throws Exception {
        var monitoring = mock(MonitoringEventService.class);
        doThrow(new IllegalStateException("private-monitoring-error")).when(monitoring)
                .recordWarn(any(), any(), anyString(), any(), any(), any(), anyMap());
        var response = new MockHttpServletResponse();
        new AccessDeniedMonitoringFilter(monitoring, new TestClock(), 30).doFilter(
                new MockHttpServletRequest("POST", "/api/auth/login"), response, (req, res) -> {
                    response.setStatus(403); response.getWriter().write("original rejection"); response.flushBuffer();
                });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("original rejection");
    }

    @Test void globalEventBudgetBoundsAttackTrafficAndRecoversAfterTheWindow() throws Exception {
        var clock = new TestClock();
        var monitoring = mock(MonitoringEventService.class);
        var filter = new AccessDeniedMonitoringFilter(monitoring, clock, 2);
        for (int i = 0; i < 100; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), response,
                    (req, res) -> response.setStatus(403));
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verify(monitoring, times(2)).recordWarn(any(), any(), anyString(), any(), any(), any(), anyMap());
        clock.now = clock.now.plusSeconds(60);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), response,
                (req, res) -> response.setStatus(403));
        verify(monitoring, times(3)).recordWarn(any(), any(), anyString(), any(), any(), any(), anyMap());
    }

    @Test void successfulUnauthorizedAndRateLimitedResponsesKeepTheirExistingOwners() throws Exception {
        var monitoring = mock(MonitoringEventService.class);
        var filter = new AccessDeniedMonitoringFilter(monitoring, new TestClock(), 30);
        for (int status : new int[]{200, 201, 302, 401, 429, 500}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response,
                    (req, res) -> response.setStatus(status));
            assertThat(response.getStatus()).isEqualTo(status);
        }
        verifyNoInteractions(monitoring);
    }
}
