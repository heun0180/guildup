package com.guildup.discord.oauth.controller;

import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.service.DiscordOAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import jakarta.servlet.http.HttpSession;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.discord.oauth.exception.InvalidDiscordOAuthStateException;

/**
 * GuildUp 커뮤니티와 Discord 계정을 연결하기 위한 OAuth 흐름을 처리한다.
 *
 * <p>전체 흐름은 다음과 같다.</p>
 * <ol>
 *     <li>연결 화면에서 {@code authorize} 엔드포인트를 호출한다.</li>
 *     <li>서버가 사용자를 Discord 인증 화면으로 리다이렉트한다.</li>
 *     <li>인증 완료 후 Discord가 {@code callback} 엔드포인트로 사용자를 돌려보낸다.</li>
 *     <li>서버가 인증 결과를 임시 저장하고 다시 연결 화면으로 리다이렉트한다.</li>
 *     <li>연결 화면이 {@code getResult} 엔드포인트를 호출해 계정과 서버 목록을 표시한다.</li>
 * </ol>
 */
@RestController
public class DiscordOAuthController {

    // Discord 인증 URL 생성, 콜백 처리, 임시 인증 결과 조회를 담당한다.
    private final DiscordOAuthService discordOAuthService;

    private final CommunityAccessService access;
    private static final String STATE = "COMMUNITY_DISCORD_STATE";
    private static final String COMMUNITY = "COMMUNITY_DISCORD_ID";

    public DiscordOAuthController(DiscordOAuthService discordOAuthService, CommunityAccessService access) {
        this.access = access;
        this.discordOAuthService = discordOAuthService;
    }

    /**
     * Discord OAuth 인증을 시작한다.
     *
     * <p>{@code discord-connect.html}에서 "Discord 연결하기" 버튼을 누르면 이 주소로
     * 이동한다. 서비스가 만든 Discord 인증 URL을 Location 헤더에 넣어 302 응답을
     * 반환하므로, 브라우저는 Discord 로그인 및 권한 승인 화면으로 이동한다.</p>
     *
     * @param communityId Discord 서버와 연결할 GuildUp 커뮤니티 ID
     * @return Discord 인증 화면으로 이동시키는 302 응답
     */
    @GetMapping("/api/communities/{communityId}/discord/oauth/authorize")
    public ResponseEntity<Void> authorize(@PathVariable Long communityId, HttpSession session) {
        access.requireManagementAccess(CurrentUserSession.requireUserId(session), communityId);
        // URL에는 client_id, scope, redirect_uri와 요청 검증용 state가 포함된다.
        URI discordAuthorizationUri = URI.create(discordOAuthService.createAuthorizationUrl(communityId));

        session.setAttribute(STATE, UriComponentsBuilder.fromUri(discordAuthorizationUri)
                .build().getQueryParams().getFirst("state"));
        session.setAttribute(COMMUNITY, communityId);

        // 응답 본문 없이 Location 헤더의 Discord URL로 브라우저를 이동시킨다.
        return ResponseEntity.status(HttpStatus.FOUND).location(discordAuthorizationUri).build();
    }

    /**
     * 사용자가 Discord에서 인증을 마친 뒤 Discord가 호출하는 콜백이다.
     *
     * <p>{@code code}를 Discord 액세스 토큰으로 교환한 다음 사용자 정보와 관리 가능한
     * 서버 목록을 조회한다. 조회 결과는 서버에 잠시 저장하고, 브라우저가 결과 ID를
     * 가지고 연결 화면으로 돌아가도록 302 응답을 반환한다.</p>
     *
     * @param code Discord가 발급한 일회용 인증 코드
     * @param state 인증 시작 요청과 콜백이 동일한 흐름인지 확인하는 임시 값
     * @return Discord 연결 화면으로 이동시키는 302 응답
     */
    @GetMapping("/api/discord/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpSession session
    ) {
        Long userId = CurrentUserSession.requireUserId(session);
        if (!state.equals(session.getAttribute(STATE))
                || !(session.getAttribute(COMMUNITY) instanceof Long communityId)) {
            throw new InvalidDiscordOAuthStateException();
        }
        access.requireManagementAccess(userId, communityId);
        session.removeAttribute(STATE);
        session.removeAttribute(COMMUNITY);
        // state로 원래 communityId를 복원하고, code로 Discord 사용자/서버 정보를 조회한다.
        DiscordOAuthService.OAuthCompletion completion =
                discordOAuthService.completeAuthorization(code, state);

        // 실제 인증 데이터를 URL에 노출하지 않고 임시 결과 식별자(oauthResult)만 전달한다.
        URI screenUri = UriComponentsBuilder.fromPath("/discord-connect.html")
                .queryParam("communityId", completion.communityId())
                .queryParam("oauthResult", completion.resultId())
                .build()
                .encode()
                .toUri();

        // 브라우저를 /discord-connect.html?communityId=...&oauthResult=... 로 이동시킨다.
        return ResponseEntity.status(HttpStatus.FOUND).location(screenUri).build();
    }

    /**
     * OAuth 콜백에서 임시 저장한 Discord 인증 결과를 반환한다.
     *
     * <p>연결 화면은 콜백 이후 URL에서 {@code oauthResult} 값을 읽고 이 API를 호출한다.
     * 응답에는 로그인한 Discord 사용자와 해당 사용자가 관리할 수 있는 서버 목록이
     * 포함된다.</p>
     *
     * @param communityId 인증을 시작한 GuildUp 커뮤니티 ID
     * @param resultId 콜백 처리 후 발급한 임시 OAuth 결과 ID
     * @return Discord 사용자 정보와 관리 가능한 서버 목록
     */
    @GetMapping("/api/communities/{communityId}/discord/oauth/results/{resultId}")
    public DiscordOAuthResultResponse getResult(
            @PathVariable Long communityId,
            @PathVariable String resultId
    ) {
        // communityId와 resultId가 일치하고 결과가 만료되지 않았는지는 서비스/저장소가 검증한다.
        return discordOAuthService.getResult(communityId, resultId);
    }
}
