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
public class AuthSessionService implements jakarta.servlet.http.HttpSessionListener {
    private final UserRepository users;
    private final java.util.concurrent.ConcurrentMap<Long, java.util.Set<HttpSession>> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthSessionService(UserRepository users) { this.users = users; }

    public void authenticate(HttpServletRequest request, User user) {
        requireActive(user);
        // Re-read after the login transaction: withdrawal may have committed in between.
        requireActive(users.findById(user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
        HttpSession session = request.getSession();
        synchronized (session) {
            request.changeSessionId();
            session.setAttribute(CurrentUserSession.USER_ID, user.getId());
            activeSessions.computeIfAbsent(user.getId(), ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(session);
            session.removeAttribute(DiscordAuthAttempt.ATTRIBUTE);
            session.removeAttribute(WithdrawalVerification.ATTRIBUTE);
            SessionCsrfTokens.rotate(session);
        }
    }

    @Transactional(readOnly = true)
    public User requireUser(HttpSession session) {
        return users.findById(CurrentUserSession.requireUserId(session)).filter(User::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
    }

    public static User requireActive(User user) {
        if (!user.isActive()) throw new com.guildup.user.auth.exception.AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다.");
        return user;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) synchronized (session) {
            forget(session);
            try { session.invalidate(); }
            catch (IllegalStateException ignored) { /* Concurrent logout already invalidated it. */ }
        }
    }

    /** 커밋 이후 같은 서버의 다른 탭/브라우저 세션도 즉시 종료한다. DB 검사로 다른 서버도 차단한다. */
    public void revokeUser(Long userId) {
        var sessions = activeSessions.remove(userId);
        if (sessions == null) return;
        for (var session : sessions) {
            // 다른 세션 모니터를 획득하면 두 탭의 동시 탈퇴와 교착될 수 있다.
            try { session.invalidate(); }
            catch (IllegalStateException ignored) { /* Already invalidated. */ }
        }
    }

    @Override public void sessionDestroyed(jakarta.servlet.http.HttpSessionEvent event) { forget(event.getSession()); }

    private void forget(HttpSession session) {
        try {
            if (session.getAttribute(CurrentUserSession.USER_ID) instanceof Long id) {
                activeSessions.computeIfPresent(id, (ignored, sessions) -> {
                    sessions.remove(session); return sessions.isEmpty() ? null : sessions;
                });
            }
        } catch (IllegalStateException ignored) { /* The container has already discarded its attributes. */ }
    }
}
