package com.guildup.user.auth.service;

import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 이메일/Discord 로그인과 로그인 수단 추가가 공유하는 유일한 세션 인증 처리다. */
@Service
public class AuthSessionService {
    private final UserRepository users;

    public AuthSessionService(UserRepository users) { this.users = users; }

    public void authenticate(HttpServletRequest request, User user) {
        HttpSession session = request.getSession();
        synchronized (session) {
            request.changeSessionId();
            session.setAttribute(CurrentUserSession.USER_ID, user.getId());
            session.removeAttribute(DiscordAuthAttempt.ATTRIBUTE);
            SessionCsrfTokens.rotate(session);
        }
    }

    @Transactional(readOnly = true)
    public User requireUser(HttpSession session) {
        return users.findById(CurrentUserSession.requireUserId(session)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) synchronized (session) { session.invalidate(); }
    }
}
