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
    public static final String AUTHENTICATION_VERSION = "LOGIN_AUTHENTICATION_VERSION";
    private final UserRepository users;
    private final java.util.concurrent.ConcurrentMap<Long, java.util.Set<HttpSession>> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthSessionService(UserRepository users) { this.users = users; }

    public void authenticate(HttpServletRequest request, User user) {
        requireActive(user);
        // Re-read after the login transaction: withdrawal may have committed in between.
        var state = users.authenticationState(user.getId()).orElseThrow(AuthSessionService::expired);
        if (state.getStatus() != com.guildup.user.domain.UserStatus.ACTIVE
                || state.getVersion() != user.getAuthenticationVersion()) throw expired();
        HttpSession session = request.getSession();
        synchronized (session) {
            request.changeSessionId();
            session.setAttribute(CurrentUserSession.USER_ID, user.getId());
            session.setAttribute(AUTHENTICATION_VERSION, state.getVersion());
            activeSessions.computeIfAbsent(user.getId(), ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(session);
            session.removeAttribute(DiscordAuthAttempt.ATTRIBUTE);
            session.removeAttribute(WithdrawalVerification.ATTRIBUTE);
            SessionCsrfTokens.rotate(session);
        }
    }

    @Transactional(readOnly = true)
    public User requireUser(HttpSession session) {
        Long id = CurrentUserSession.requireUserId(session);
        if (!isSessionCurrent(session, id)) throw expired();
        return users.findById(id).filter(User::isActive).orElseThrow(AuthSessionService::expired);
    }

    /** Long-lived SSE delivery also checks the DB version without loading an entity on every poll. */
    @Transactional(readOnly = true)
    public boolean isSessionCurrent(HttpSession session, Long id) {
        Object version;
        try {
            if (!id.equals(CurrentUserSession.requireUserId(session))) return false;
            version = session.getAttribute(AUTHENTICATION_VERSION);
        } catch (ResponseStatusException | IllegalStateException invalidated) { return false; }
        var state = users.authenticationState(id).orElse(null);
        if (state == null) return false;
        // 배포 전 세션은 버전 0으로만 인정한다. 첫 인증 변경 이후에는 모두 거부한다.
        long sessionVersion = version instanceof Long value ? value : 0;
        return state.getStatus() == com.guildup.user.domain.UserStatus.ACTIVE && sessionVersion == state.getVersion();
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 정보가 변경되었습니다. 다시 로그인해 주세요.");
    }

    /** 새 비밀번호로 이미 재로그인한 세션은 보존한다. 다른 서버의 세션은 requireUser가 거부한다. */
    public void revokeBeforeVersion(Long userId, long version) {
        var sessions = activeSessions.get(userId);
        if (sessions == null) return;
        for (var session : sessions) {
            try {
                Object stored = session.getAttribute(AUTHENTICATION_VERSION);
                if ((stored instanceof Long value ? value : 0) < version) session.invalidate();
            } catch (IllegalStateException ignored) { /* Concurrent logout. */ }
        }
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
