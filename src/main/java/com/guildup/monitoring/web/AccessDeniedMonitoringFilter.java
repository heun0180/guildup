package com.guildup.monitoring.web;

import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;

/** Observe application 403s without changing authorization or retaining request secrets. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AccessDeniedMonitoringFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccessDeniedMonitoringFilter.class);
    private static final String DENIAL = AccessDeniedMonitoringFilter.class.getName() + ".denial";

    public enum Denial {
        CSRF_HEADER_MISSING("SESSION_CSRF", "SESSION_CSRF", "HEADER_MISSING"),
        CSRF_SESSION_MISSING("SESSION_CSRF", "SESSION_CSRF", "SESSION_MISSING"),
        CSRF_SESSION_NOT_FOUND("SESSION_CSRF", "SESSION_CSRF", "SESSION_EXPIRED_OR_UNKNOWN"),
        CSRF_MISMATCH("SESSION_CSRF", "SESSION_CSRF", "TOKEN_MISMATCH"),
        SYSTEM_ADMIN_REQUIRED("MVC_AUTHORIZATION", "SYSTEM_ADMIN_REQUIRED", "INSUFFICIENT_SYSTEM_ROLE"),
        EMAIL_VERIFICATION_REQUIRED("AUTH_POLICY", "EMAIL_VERIFICATION_REQUIRED", "EMAIL_NOT_VERIFIED"),
        WITHDRAWAL_VERIFICATION_REQUIRED("AUTH_POLICY", "WITHDRAWAL_VERIFICATION_REQUIRED", "IDENTITY_VERIFICATION_REQUIRED"),
        APPLICATION_ACCESS_DENIED("APPLICATION", "APPLICATION_ACCESS_DENIED", "UNCLASSIFIED_ACCESS_DENIAL");

        final String layer;
        final String rule;
        final String reason;

        Denial(String layer, String rule, String reason) {
            this.layer = layer; this.rule = rule; this.reason = reason;
        }
    }

    public static void mark(HttpServletRequest request, Denial denial) { request.setAttribute(DENIAL, denial); }

    private final MonitoringEventService monitoring;
    private final Clock clock;
    private final int maxPerMinute;
    private long windowStart = Long.MIN_VALUE;
    private int emitted;

    public AccessDeniedMonitoringFilter(MonitoringEventService monitoring, Clock clock,
            @Value("${security.diagnostics.max-events-per-minute:30}") int maxPerMinute) {
        if (maxPerMinute < 1 || maxPerMinute > 1000) throw new IllegalArgumentException("Invalid access denial event limit");
        this.monitoring = monitoring; this.clock = clock; this.maxPerMinute = maxPerMinute;
    }

    private synchronized boolean reserve() {
        long now = clock.millis();
        if (windowStart == Long.MIN_VALUE || now - windowStart >= 60_000) { windowStart = now; emitted = 0; }
        if (emitted >= maxPerMinute) return false;
        emitted++;
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        finally {
            if (response.getStatus() == 403 && !request.isAsyncStarted()) {
                try { record(request); }
                catch (RuntimeException ignored) { /* Diagnostics must never replace the original response. */ }
            }
        }
    }

    private void record(HttpServletRequest request) {
        if (!reserve()) return;
        Denial denial = request.getAttribute(DENIAL) instanceof Denial value ? value : Denial.APPLICATION_ACCESS_DENIED;
        // The matched route template excludes query strings, matrix parameters and dynamic identifiers.
        String endpoint = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route
                ? route : "[unmapped]";
        String method = request.getMethod().matches("[A-Z]{1,12}") ? request.getMethod() : "OTHER";
        String requestId = (String) request.getAttribute(RequestLogContextFilter.REQUEST_ID);
        boolean sessionPresent;
        try { sessionPresent = request.getSession(false) != null; }
        catch (IllegalStateException expired) { sessionPresent = false; }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("endpoint", endpoint);
        metadata.put("method", method);
        metadata.put("status", 403);
        metadata.put("layer", denial.layer);
        metadata.put("rule", denial.rule);
        metadata.put("reason", denial.reason);
        metadata.put("sessionPresent", sessionPresent);
        metadata.put("requestedSessionPresent", request.getRequestedSessionId() != null);
        metadata.put("requestedSessionValid", request.isRequestedSessionIdValid());
        metadata.put("secureRequest", request.isSecure());
        metadata.put("requestId", requestId);
        metadata.put("sampled", true);
        log.warn("Application access denied - status=403 layer={} rule={} reason={} method={} endpoint={} requestId={} sessionPresent={} requestedSessionValid={} secureRequest={}",
                denial.layer, denial.rule, denial.reason, method, endpoint, requestId, sessionPresent,
                request.isRequestedSessionIdValid(), request.isSecure());
        monitoring.recordWarn(MonitoringCategory.SECURITY, MonitoringEventCode.HTTP_ACCESS_DENIED,
                "Application request denied", null, null, requestId, metadata);
    }
}
