package com.guildup.discord.oauth.service;

import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiGuild;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.dto.DiscordOAuthUserResponse;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

/**
 * Discord 사용자 OAuth의 시작부터 결과 저장까지 담당하는 서비스다.
 * 인증 URL 생성, 콜백 검증, 토큰 교환, 사용자/서버 조회를 하나의 흐름으로 묶는다.
 */
@Service
public class DiscordOAuthService {

    private static final String DISCORD_AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    // Discord 권한 비트 필드에서 Administrator는 3번 비트다.
    private static final BigInteger ADMINISTRATOR = BigInteger.ONE.shiftLeft(3);
    // Discord 권한 비트 필드에서 Manage Guild는 5번 비트다.
    private static final BigInteger MANAGE_GUILD = BigInteger.ONE.shiftLeft(5);

    private final CommunityRepository communityRepository;
    private final DiscordOAuthProperties properties;
    private final DiscordOAuthSessionStore sessionStore;
    private final DiscordApiClient discordApiClient;

    public DiscordOAuthService(
            CommunityRepository communityRepository,
            DiscordOAuthProperties properties,
            DiscordOAuthSessionStore sessionStore,
            DiscordApiClient discordApiClient
    ) {
        this.communityRepository = communityRepository;
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.discordApiClient = discordApiClient;
    }

    /**
     * 연결할 커뮤니티를 확인하고 Discord 사용자 인증 URL을 만든다.
     * state에는 원래 communityId가 서버 측에서 연결되어 콜백 위조와 대상 혼동을 방지한다.
     */
    public String createAuthorizationUrl(Long communityId) {
        if (!communityRepository.existsById(communityId)) {
            throw new CommunityNotFoundException(communityId);
        }
        properties.validate();
        String state = sessionStore.createState(communityId);

        // identify는 사용자 프로필, guilds는 참여 서버와 해당 서버 권한 조회에 필요하다.
        return UriComponentsBuilder.fromUriString(DISCORD_AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("scope", "identify guilds")
                .queryParam("state", state)
                .queryParam("redirect_uri", properties.redirectUri())
                .build()
                .encode()
                .toUriString();
    }

    /** Discord 콜백을 완료하고 화면에서 조회할 수 있는 임시 결과 ID를 발급한다. */
    public OAuthCompletion completeAuthorization(String code, String state) {
        properties.validate();
        // state는 저장소에서 꺼내는 즉시 삭제되므로 동일 콜백을 다시 사용할 수 없다.
        Long communityId = sessionStore.consumeState(state);

        // 브라우저에는 액세스 토큰을 전달하지 않고 백엔드가 Discord API를 직접 호출한다.
        DiscordAccessTokenResponse token = discordApiClient.exchangeCode(code);
        DiscordApiUser user = discordApiClient.getCurrentUser(token.accessToken());
        List<DiscordManageableGuildResponse> guilds = discordApiClient
                .getCurrentUserGuilds(token.accessToken()).stream()
                // 사용자가 연결을 관리할 수 있는 서버만 선택 화면에 노출한다.
                .filter(this::isManageable)
                .map(this::toGuildResponse)
                .sorted(Comparator.comparing(
                        DiscordManageableGuildResponse::name,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();

        // 사용자/서버 원본 대신 화면에 필요한 값만 변환해 짧은 시간 동안 보관한다.
        DiscordOAuthResultResponse result = new DiscordOAuthResultResponse(
                toUserResponse(user),
                guilds
        );
        String resultId = sessionStore.saveResult(communityId, result);
        return new OAuthCompletion(communityId, resultId);
    }

    /** 콜백 후 발급된 결과 ID로 화면 표시용 OAuth 결과를 조회한다. */
    public DiscordOAuthResultResponse getResult(Long communityId, String resultId) {
        return sessionStore.getResult(communityId, resultId);
    }

    /** 서버 소유자이거나 Administrator 또는 Manage Guild 권한이 있는지 판별한다. */
    private boolean isManageable(DiscordApiGuild guild) {
        if (guild.owner()) {
            return true;
        }

        try {
            BigInteger permissions = new BigInteger(guild.permissions());
            return permissions.and(ADMINISTRATOR).signum() != 0
                    || permissions.and(MANAGE_GUILD).signum() != 0;
            // 권한 값이 없거나 숫자로 해석할 수 없으면 안전하게 관리 불가로 처리한다.
        } catch (NullPointerException | NumberFormatException exception) {
            return false;
        }
    }

    /** Discord 사용자 API 응답을 외부 응답 DTO로 변환한다. */
    private DiscordOAuthUserResponse toUserResponse(DiscordApiUser user) {
        return new DiscordOAuthUserResponse(
                user.id(),
                user.username(),
                user.globalName(),
                avatarUrl(user)
        );
    }

    /** 서버 아이콘 해시를 Discord CDN URL로 조합해 외부 응답 DTO로 변환한다. */
    private DiscordManageableGuildResponse toGuildResponse(DiscordApiGuild guild) {
        String iconUrl = guild.icon() == null
                ? null
                : "https://cdn.discordapp.com/icons/" + guild.id() + "/" + guild.icon() + ".webp";
        return new DiscordManageableGuildResponse(guild.id(), guild.name(), iconUrl, guild.owner());
    }

    /** 사용자 아바타 해시가 애니메이션 형식인지에 따라 CDN 확장자를 결정한다. */
    private String avatarUrl(DiscordApiUser user) {
        if (user.avatar() == null) {
            return null;
        }
        String extension = user.avatar().startsWith("a_") ? "gif" : "webp";
        return "https://cdn.discordapp.com/avatars/"
                + user.id() + "/" + user.avatar() + "." + extension;
    }

    /** 콜백 처리 후 컨트롤러가 연결 화면 URL을 만들 때 사용하는 내부 결과다. */
    public record OAuthCompletion(Long communityId, String resultId) {
    }
}
