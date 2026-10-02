package com.guildup.monitoring.web;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.monitoring.logging.SafeLogText;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HttpServerErrorMonitoringFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpServerErrorMonitoringFilter.class);
    private static final Pattern COMMUNITY_PATH = Pattern.compile("/(?:api/)?communities/(\\d+)(?:/|$)");
    private final MonitoringEventService monitoring;

    public HttpServerErrorMonitoringFilter(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failure = exception;
            if (!response.isCommitted()) response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            throw exception;
        } finally {
            int status = response.getStatus();
            if (failure == null && request.getAttribute(FailureLogContext.FAILURE) instanceof Throwable captured)
                failure = captured;
            if (status >= 500 && !request.isAsyncStarted()) {
                try { recordHttpFailure(request, status, elapsedMs(started), failure); }
                catch (RuntimeException monitoringFailure) {
                    log.warn("HTTP failure monitoring unavailable - method={} endpoint={}",
                            request.getMethod(), SafeLogText.endpoint(request.getRequestURI()), monitoringFailure);
                }
            }
        }
    }

    private void recordHttpFailure(HttpServletRequest request, int status, long elapsedMs, Throwable failure) {
        Long userId = userId(request);
        Long communityId = communityId(request.getRequestURI());
        String endpoint = SafeLogText.endpoint(request.getRequestURI());
        if (!FailureLogContext.isLogged(failure)) {
            log.error("HTTP request failed - method={} endpoint={} status={} communityId={} userId={} elapsedMs={}",
                    request.getMethod(), endpoint, status, communityId, userId, elapsedMs, failure);
            FailureLogContext.markLogged(failure);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("method", request.getMethod());
        metadata.put("uri", endpoint);
        metadata.put("status", status);
        metadata.put("elapsedMs", elapsedMs);
        metadata.put("requestId", request.getAttribute(RequestLogContextFilter.REQUEST_ID));
        if (failure != null) metadata.put("exceptionClass", root(failure).getClass().getSimpleName());
        monitoring.recordError(MonitoringCategory.HTTP, MonitoringEventCode.HTTP_5XX,
                request.getMethod() + " " + endpoint + " returned " + status,
                communityId, userId, request.getMethod() + " " + endpoint, metadata);
        if (failure != null && isDatabaseFailure(failure)) {
            monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                    "Database operation failed while handling an HTTP request", communityId, userId,
                    request.getMethod() + " " + endpoint, Map.of(
                            "endpoint", endpoint, "status", status,
                            "elapsedMs", elapsedMs, "exceptionClass", root(failure).getClass().getSimpleName()));
        }
        if (failure != null && !isDatabaseFailure(failure)) monitoring.recordError(MonitoringCategory.SYSTEM,
                MonitoringEventCode.UNEXPECTED_EXCEPTION, "Server operation failed", communityId, userId,
                request.getMethod() + " " + endpoint, metadata);
    }

    private Long userId(HttpServletRequest request) {
        if (request.getAttribute(RequestLogContextFilter.USER_ID) instanceof Long id) return id;
        try {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long id) return id;
        } catch (IllegalStateException expiredSession) {
            // Reading monitoring context must never replace the original failure on concurrent logout.
        }
        return null;
    }

    private Long communityId(String uri) {
        Matcher matcher = COMMUNITY_PATH.matcher(uri);
        if (!matcher.find()) return null;
        try { return Long.valueOf(matcher.group(1)); } catch (NumberFormatException ignored) { return null; }
    }

    private boolean isDatabaseFailure(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException || current instanceof PersistenceException
                    || current instanceof DataAccessException) return true;
        }
        return false;
    }

    private Throwable root(Throwable failure) {
        Throwable current = failure;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current.getCause() != null && seen.add(current.getCause())) current = current.getCause();
        return current;
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
