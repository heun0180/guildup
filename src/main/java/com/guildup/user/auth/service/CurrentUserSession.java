package com.guildup.user.auth.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 기존 로그인 Session을 읽는 공통 진입점이다. */
public final class CurrentUserSession {
    public static final String USER_ID = "LOGIN_USER_ID";
    private CurrentUserSession() {}

    public static Long requireUserId(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(USER_ID);
        if (!(value instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required");
        }
        return userId;
    }
}
