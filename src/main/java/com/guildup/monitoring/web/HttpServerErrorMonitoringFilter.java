package com.guildup.monitoring.web;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
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
            if (status >= 500) recordHttpFailure(request, status, elapsedMs(started), failure);
        }
    }

    private void recordHttpFailure(HttpServletRequest request, int status, long elapsedMs, Throwable failure) {
        Long userId = null;
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long id) userId = id;
        Long communityId = communityId(request.getRequestURI());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("method", request.getMethod());
        metadata.put("uri", request.getRequestURI());
        metadata.put("status", status);
        metadata.put("elapsedMs", elapsedMs);
        if (failure != null) metadata.put("exceptionClass", root(failure).getClass().getSimpleName());
        monitoring.recordError(MonitoringCategory.HTTP, MonitoringEventCode.HTTP_5XX,
                request.getMethod() + " " + request.getRequestURI() + " returned " + status,
                communityId, userId, request.getMethod() + " " + request.getRequestURI(), metadata);
        if (failure != null && isDatabaseFailure(failure)) {
            monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                    "Database operation failed while handling an HTTP request", communityId, userId,
                    request.getMethod() + " " + request.getRequestURI(), Map.of(
                            "endpoint", request.getRequestURI(), "status", status,
                            "elapsedMs", elapsedMs, "exceptionClass", root(failure).getClass().getSimpleName()));
        }
    }

    private Long communityId(String uri) {
        Matcher matcher = COMMUNITY_PATH.matcher(uri);
        if (!matcher.find()) return null;
        try { return Long.valueOf(matcher.group(1)); } catch (NumberFormatException ignored) { return null; }
    }

    private boolean isDatabaseFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException || current instanceof PersistenceException
                    || current instanceof DataAccessException) return true;
        }
        return false;
    }

    private Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
