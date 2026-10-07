package com.guildup.user.auth.controller;

import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** 개인 Discord 인증만 담당한다. 커뮤니티 서버 OAuth와 별개의 세션 문맥이다. */
@RestController
@RequestMapping("/api/auth")
public class DiscordLoginController {
    private static final Logger log = LoggerFactory.getLogger(DiscordLoginController.class);
    private final SecureRandom random = new SecureRandom();
    private final DiscordOAuthProperties properties;
    private final DiscordLoginService discord;
    private final AuthSessionService sessions;
    private MonitoringEventService monitoring;

    public DiscordLoginController(DiscordOAuthProperties properties, DiscordLoginService discord, AuthSessionService sessions) {
        this.properties = properties;
        this.discord = discord;
        this.sessions = sessions;
    }

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    @GetMapping("/discord/authorize")
    public ResponseEntity<Void> authorize(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(CurrentUserSession.USER_ID) instanceof Long) return redirect("/communities.html");
            return redirect(start(session, DiscordAuthAttempt.Purpose.LOGIN, null).toString());
        }
    }

    /** CSRF로 보호된 명시적 계정 연결 시작이다. */
    @PostMapping("/discord/link")
    public ResponseEntity<LinkStartResponse> link(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            Long userId = sessions.requireUser(session).getId();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new LinkStartResponse(start(session, DiscordAuthAttempt.Purpose.LINK_ACCOUNT, userId).toString()));
        }
    }

    private URI start(HttpSession session, DiscordAuthAttempt.Purpose purpose, Long userId) {
        properties.validate();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String redirectUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/auth/discord/callback").build().toUriString();
        session.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt(state, redirectUri, purpose, userId, Instant.now()));
        return UriComponentsBuilder.fromUriString("https://discord.com/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", properties.clientId())
                .queryParam("scope", "identify").queryParam("state", state).queryParam("redirect_uri", redirectUri)
                .build().encode().toUri();
    }

    @GetMapping("/discord/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        DiscordAuthAttempt attempt = consume(session);
        boolean linking = attempt != null && attempt.purpose() == DiscordAuthAttempt.Purpose.LINK_ACCOUNT;
        String errorPage = linking ? "/account.html" : "/login.html";
        if (attempt == null || !attempt.matches(state) || !boundToCurrentUser(session, attempt)) {
            recordRejectedLogin();
            return errorRedirect(errorPage, "session");
        }
        if (error != null || code == null || code.isBlank()) return errorRedirect(errorPage, "discord");

        var discordUser = discord.getDiscordUser(code, attempt.redirectUri());
        synchronized (session) {
            // token 교환 중 로그아웃/다른 로그인으로 문맥이 바뀐 경우도 거부한다.
            if (!boundToCurrentUser(session, attempt)) return errorRedirect(errorPage, "session");
            try {
                var user = linking ? discord.linkAccount(attempt.userId(), discordUser) : discord.findOrCreateUser(discordUser);
                sessions.authenticate(request, user);
                return redirect(linking ? "/account.html?discordLinked=true" : "/communities.html");
            } catch (AuthException exception) {
                if (!linking) throw exception;
                return errorRedirect(errorPage, exception.getCode());
            }
        }
    }

    private DiscordAuthAttempt consume(HttpSession session) {
        if (session == null) return null;
        synchronized (session) {
            try {
                Object attempt = session.getAttribute(DiscordAuthAttempt.ATTRIBUTE);
                session.removeAttribute(DiscordAuthAttempt.ATTRIBUTE);
                return attempt instanceof DiscordAuthAttempt value ? value : null;
            } catch (IllegalStateException exception) { return null; }
        }
    }

    private boolean boundToCurrentUser(HttpSession session, DiscordAuthAttempt attempt) {
        if (session == null) return false;
        try {
            return Objects.equals(session.getAttribute(CurrentUserSession.USER_ID), attempt.userId());
        } catch (IllegalStateException exception) { return false; }
    }

    private void recordRejectedLogin() {
        log.warn("Discord authentication callback rejected. reason=INVALID_SESSION_CONTEXT");
        if (monitoring != null) monitoring.recordWarn(MonitoringCategory.DISCORD, MonitoringEventCode.DISCORD_OAUTH_FAILED,
                "Discord authentication callback validation failed", null, null, "discordLogin",
                java.util.Map.of("reason", "INVALID_SESSION_CONTEXT"));
    }

    private ResponseEntity<Void> errorRedirect(String page, String reason) {
        return redirect(UriComponentsBuilder.fromPath(page).queryParam("oauthError", reason).build().encode().toUriString());
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).location(URI.create(location)).build();
    }

    public record LinkStartResponse(String authorizationUrl) {}
}
