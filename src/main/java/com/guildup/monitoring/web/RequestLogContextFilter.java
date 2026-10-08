package com.guildup.monitoring.web;

import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.SafeLogText;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogContextFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = RequestLogContextFilter.class.getName() + ".requestId";
    public static final String USER_ID = RequestLogContextFilter.class.getName() + ".userId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern COMMUNITY = Pattern.compile("/(?:api/)?communities/(\\d+)(?:/|$)");

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String id = (String) request.getAttribute(REQUEST_ID);
        if (id == null) {
            // Authentication diagnostics use server-generated IDs; user supplied values may contain secrets.
            String supplied = authenticationRequest(request) ? null : request.getHeader("X-Request-ID");
            id = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, id);
        }
        response.setHeader("X-Request-ID", id);
        var context = new LinkedHashMap<String, Object>();
        context.put("requestId", id);
        context.put("method", request.getMethod());
        context.put("endpoint", SafeLogText.endpoint(request.getRequestURI()));
        var community = COMMUNITY.matcher(request.getRequestURI());
        if (community.find()) context.put("communityId", community.group(1));
        try {
            var session = request.getSession(false);
            if (session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long userId) {
                context.put("userId", userId);
                request.setAttribute(USER_ID, userId);
            }
        } catch (IllegalStateException expiredSession) {
            // Concurrent logout may invalidate the session while diagnostic context is read.
        }
        try (var ignored = LogContext.scope(context)) { chain.doFilter(request, response); }
    }

    private boolean authenticationRequest(HttpServletRequest request) {
        try {
            String path = org.springframework.web.util.UriUtils.decode(
                    request.getRequestURI().substring(request.getContextPath().length()), java.nio.charset.StandardCharsets.UTF_8);
            path = path.replaceAll(";[^/]*", "");
            return path.startsWith("/api/auth/");
        } catch (IllegalArgumentException malformedPath) { return true; }
    }
}
