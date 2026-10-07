package com.guildup.user.auth.service;

import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Session synchronizer token, independent of OAuth state; never stored in URLs or logs. */
public final class SessionCsrfTokens {
    public static final String HEADER = "X-CSRF-Token";
    private static final String ATTRIBUTE = SessionCsrfTokens.class.getName() + ".TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionCsrfTokens() {}

    public static String getOrCreate(HttpSession session) {
        synchronized (session) {
            try {
                Object existing = session.getAttribute(ATTRIBUTE);
                return existing instanceof String token ? token : rotate(session);
            } catch (IllegalStateException expired) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Login is required");
            }
        }
    }

    public static String rotate(HttpSession session) {
        synchronized (session) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(ATTRIBUTE, token);
            return token;
        }
    }

    public static boolean matches(HttpSession session, String supplied) {
        Object stored;
        try { stored = session.getAttribute(ATTRIBUTE); }
        catch (IllegalStateException expired) { return false; }
        return stored instanceof String token && supplied != null && supplied.length() == token.length()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
