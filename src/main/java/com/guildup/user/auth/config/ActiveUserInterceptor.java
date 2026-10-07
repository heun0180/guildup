package com.guildup.user.auth.config;

import com.guildup.user.auth.service.AuthSessionService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;

/** 잔존 세션을 사용하는 모든 API(레거시 CurrentUserSession 포함)의 공통 방어선. */
public class ActiveUserInterceptor implements HandlerInterceptor {
    private final AuthSessionService sessions;
    public ActiveUserInterceptor(AuthSessionService sessions) { this.sessions = sessions; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var session = request.getSession(false);
        boolean loggedIn;
        try { loggedIn = session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long; }
        catch (IllegalStateException exception) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Login is required");
        }
        if (loggedIn) {
            try { sessions.requireUser(session); }
            catch (org.springframework.web.server.ResponseStatusException exception) {
                sessions.logout(request);
                throw exception;
            }
        }
        return true;
    }
}
