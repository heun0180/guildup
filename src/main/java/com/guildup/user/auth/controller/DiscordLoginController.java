package com.guildup.user.auth.controller;


import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import jakarta.servlet.http.HttpSession;
import com.guildup.user.auth.service.CurrentUserSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;

import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.service.DiscordLoginService;
import com.guildup.user.domain.User;
import com.guildup.user.auth.dto.LoginUserResponse;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api/auth")
public class DiscordLoginController {

    // Discord OAuth 인증 페이지 주소
    private static final String DISCORD_AUTHORIZE_URL =
            "https://discord.com/oauth2/authorize";

    // 로그인 요청 시 생성한 OAuth state를 Session에 저장할 때 사용하는 키
    private static final String DISCORD_LOGIN_STATE =
            "DISCORD_LOGIN_STATE";

    // 로그인 callback 주소를 Session에 저장할 때 사용하는 키
    private static final String DISCORD_LOGIN_REDIRECT_URI =
            "DISCORD_LOGIN_REDIRECT_URI";

    // 예측하기 어려운 OAuth state 값을 만들기 위한 난수 생성기
    private final SecureRandom secureRandom = new SecureRandom();

    // 기존 Discord OAuth 환경설정 재사용
    private final DiscordOAuthProperties discordOAuthProperties;

    private final DiscordLoginService discordLoginService;

    public DiscordLoginController(
            DiscordOAuthProperties discordOAuthProperties,
            DiscordLoginService discordLoginService
    ) {
        this.discordOAuthProperties = discordOAuthProperties;
        this.discordLoginService = discordLoginService;
    }

    private static final String LOGIN_USER_ID =
            CurrentUserSession.USER_ID;


    /**
     * Discord 로그인 시작 API
     *
     * 1. OAuth state 생성
     * 2. state와 callback 주소를 Session에 저장
     * 3. Discord OAuth 인증 URL 생성
     * 4. Discord 로그인 페이지로 리다이렉트
     */
    @GetMapping("/discord/authorize")
    public ResponseEntity<Void> authorize(HttpSession session) {

        // Discord OAuth 환경변수가 정상 설정되어 있는지 확인
        discordOAuthProperties.validate();

        // CSRF 공격 방지를 위한 로그인 전용 state 생성
        String state = createState();

        // 로그인 완료 후 Discord가 돌아올 callback 주소 생성
        String redirectUri = createLoginRedirectUri();

        // callback 요청에서 검증할 수 있도록 Session에 저장
        session.setAttribute(DISCORD_LOGIN_STATE, state);
        session.setAttribute(DISCORD_LOGIN_REDIRECT_URI, redirectUri);

        // Discord OAuth 인증 페이지 URL 생성
        URI authorizationUri = UriComponentsBuilder
                .fromUriString(DISCORD_AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", discordOAuthProperties.clientId())

                // 로그인에서는 Discord 사용자 정보만 필요하므로 identify만 요청
                .queryParam("scope", "identify")

                // callback에서 로그인 요청 위조 여부를 확인하기 위한 값
                .queryParam("state", state)

                // Discord 인증 완료 후 돌아올 주소
                .queryParam("redirect_uri", redirectUri)
                .build()
                .encode()
                .toUri();

        // 브라우저를 Discord 로그인 페이지로 이동시킴
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(authorizationUri)
                .build();
    }

    /**
     * Discord 로그인 callback
     *
     * 1. OAuth state 검증
     * 2. Discord 사용자 조회
     * 3. 기존 GuildUp 회원 조회 또는 신규 생성
     * 4. Session 로그인
     * 5. 내 커뮤니티 목록으로 이동
     */
    @GetMapping("/discord/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpSession session
    ) {
        validateAndConsumeState(session, state);

        String redirectUri =
                consumeLoginRedirectUri(session);

        DiscordApiUser discordUser =
                discordLoginService.getDiscordUser(
                        code,
                        redirectUri
                );

        User user =
                discordLoginService.findOrCreateUser(discordUser);

        session.setAttribute(
                LOGIN_USER_ID,
                user.getId()
        );

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create("/communities.html"))
                .build();
    }

    /**
     * 로그인 시작 시 Session에 저장한 state와
     * Discord callback으로 전달된 state가 같은지 확인한다.
     */
    private void validateAndConsumeState(
            HttpSession session,
            String receivedState
    ) {
        String savedState =
                (String) session.getAttribute(DISCORD_LOGIN_STATE);

        if (savedState == null || !savedState.equals(receivedState)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Discord login state is invalid"
            );
        }

        session.removeAttribute(DISCORD_LOGIN_STATE);
    }

    /**
     * OAuth 요청 위조 방지를 위한 랜덤 state 생성
     */
    private String createState() {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    /**
     * 현재 서버 주소를 기준으로 로그인 callback URL 생성
     *
     * 예:
     * http://localhost:8080/api/auth/discord/callback
     */
    private String createLoginRedirectUri() {

        return ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/api/auth/discord/callback")
                .build()
                .toUriString();
    }

    private String consumeLoginRedirectUri(HttpSession session) {
        String redirectUri =
                (String) session.getAttribute(
                        DISCORD_LOGIN_REDIRECT_URI
                );

        session.removeAttribute(
                DISCORD_LOGIN_REDIRECT_URI
        );

        if (redirectUri == null || redirectUri.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Discord login redirect URI is missing"
            );
        }

        return redirectUri;
    }

    @GetMapping("/me")
    public LoginUserResponse me(HttpSession session) {
        Long userId = CurrentUserSession.requireUserId(session);

        User user = discordLoginService
                .findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Login user does not exist"
                ));

        return LoginUserResponse.from(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        return ResponseEntity.noContent().build();
    }
}