package com.guildup.user.auth.config;

import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.auth.service.SessionCsrfTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

public class SessionCsrfInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OAuth browser redirects are GET and retain their existing session-bound state checks.
        if (!(handler instanceof HandlerMethod) || SAFE_METHODS.contains(request.getMethod())) return true;
        HttpSession session = request.getSession(false);
        // Anonymous/public requests retain the existing authentication policy; no new session is created.
        if (session == null || !(session.getAttribute(CurrentUserSession.USER_ID) instanceof Long)) return true;

        String supplied = request.getHeader(SessionCsrfTokens.HEADER);
        if (SessionCsrfTokens.matches(session, supplied)) return true;

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String code = supplied == null || supplied.isBlank() ? "CSRF_TOKEN_MISSING" : "CSRF_TOKEN_INVALID";
        // Fixed payload keeps tokens out of exception messages and monitoring logs.
        response.getWriter().write("{\"code\":\"" + code
                + "\",\"message\":\"요청 보안 정보를 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요.\"}");
        return false;
    }
}
