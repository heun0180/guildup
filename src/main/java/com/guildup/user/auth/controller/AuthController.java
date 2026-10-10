package com.guildup.user.auth.controller;

import com.guildup.user.auth.dto.*;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthSessionService sessions;
    private final CredentialAuthService credentials;
    private final AccountSettingsService accounts;
    private final com.guildup.user.auth.security.ProtectedEmailLoginService protectedLogin;

    public AuthController(AuthSessionService sessions, CredentialAuthService credentials, AccountSettingsService accounts,
                          com.guildup.user.auth.security.ProtectedEmailLoginService protectedLogin) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.accounts = accounts;
        this.protectedLogin = protectedLogin;
    }

    @GetMapping("/me")
    public ResponseEntity<LoginUserResponse> me(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LoginUserResponse.from(sessions.requireUser(request.getSession(false))));
    }

    @PostMapping("/signup")
    public ResponseEntity<LoginUserResponse> signup(@RequestBody SignupRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (session) {
            requireAnonymous(session);
            var user = credentials.register(body);
            sessions.authenticate(request, user);
            sessions.recordLogin(user, session);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(LoginUserResponse.from(user));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<LoginUserResponse> login(@RequestBody EmailLoginRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (session) {
            requireAnonymous(session);
            var user = protectedLogin.login(body, request);
            sessions.authenticate(request, user);
            sessions.recordLogin(user, session);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginUserResponse.from(user));
        }
    }

    @GetMapping("/account")
    public ResponseEntity<AccountSettingsService.AccountResponse> account(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.get(request.getSession(false)));
    }

    @PostMapping("/credentials")
    public ResponseEntity<LoginUserResponse> addCredential(@RequestBody CredentialRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            var user = credentials.addCredential(CurrentUserSession.requireUserId(session), body);
            sessions.authenticate(request, user);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(LoginUserResponse.from(user));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(HttpServletRequest request) {
        // 로그인 전 토큰을 발급해 회원가입/로그인도 같은 CSRF 검증을 거친다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(SessionCsrfTokens.getOrCreate(request.getSession())));
    }

    private void requireAnonymous(HttpSession session) {
        if (session.getAttribute(CurrentUserSession.USER_ID) instanceof Long) {
            throw new AuthException(HttpStatus.CONFLICT, "ALREADY_LOGGED_IN",
                    "이미 로그인되어 있습니다. 기존 계정에 이메일 로그인을 추가하려면 계정 설정을 이용해 주세요.");
        }
    }

    public record CsrfTokenResponse(String token) {}
}
