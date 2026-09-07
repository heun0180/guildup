package com.guildup.discord.oauth.service;

import com.guildup.community.service.DiscordCommunityConnectionService;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.discord.oauth.dto.DiscordBotInstallConfirmResponse;
import com.guildup.discord.oauth.dto.DiscordBotInstallStartResponse;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.exception.DiscordBotNotInstalledException;
import com.guildup.discord.oauth.store.DiscordBotInstallSession;
import com.guildup.discord.oauth.store.DiscordBotInstallStore;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import com.guildup.discord.service.DiscordGuildService;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth로 검증된 Discord 서버에 GuildUp 봇을 초대하고 커뮤니티 연결을 확정한다.
 * 사용자 OAuth와 봇 초대는 별도 단계이므로 설치 확인용 임시 세션을 사용한다.
 */
@Service
public class DiscordBotInstallService {

    private static final String DISCORD_AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    // Discord 설치 직후 JDA 캐시에 서버가 반영되는 데 걸리는 시간을 고려한 재시도 설정이다.
    private static final int DEFAULT_GUILD_CHECK_ATTEMPTS = 5;
    private static final long DEFAULT_GUILD_CHECK_DELAY_MILLIS = 500;

    private final DiscordOAuthProperties properties;
    private final DiscordOAuthSessionStore oauthSessionStore;
    private final DiscordBotInstallStore botInstallStore;
    private final DiscordGuildService discordGuildService;
    private final DiscordCommunityConnectionService connectionService;
    private final int guildCheckAttempts;
    private final long guildCheckDelayMillis;

    /** 운영 시 기본 재시도 횟수와 대기 시간을 사용하는 Spring 생성자다. */
    @Autowired
    public DiscordBotInstallService(
            DiscordOAuthProperties properties,
            DiscordOAuthSessionStore oauthSessionStore,
            DiscordBotInstallStore botInstallStore,
            DiscordGuildService discordGuildService,
            DiscordCommunityConnectionService connectionService
    ) {
        this(
                properties,
                oauthSessionStore,
                botInstallStore,
                discordGuildService,
                connectionService,
                DEFAULT_GUILD_CHECK_ATTEMPTS,
                DEFAULT_GUILD_CHECK_DELAY_MILLIS
        );
    }

    /** 테스트에서 재시도 횟수와 대기 시간을 작게 조정할 수 있는 패키지 전용 생성자다. */
    DiscordBotInstallService(
            DiscordOAuthProperties properties,
            DiscordOAuthSessionStore oauthSessionStore,
            DiscordBotInstallStore botInstallStore,
            DiscordGuildService discordGuildService,
            DiscordCommunityConnectionService connectionService,
            int guildCheckAttempts,
            long guildCheckDelayMillis
    ) {
        this.properties = properties;
        this.oauthSessionStore = oauthSessionStore;
        this.botInstallStore = botInstallStore;
        this.discordGuildService = discordGuildService;
        this.connectionService = connectionService;
        this.guildCheckAttempts = guildCheckAttempts;
        this.guildCheckDelayMillis = guildCheckDelayMillis;
    }

    /**
     * OAuth 결과에서 선택한 서버를 검증하고 봇 설치를 시작한다.
     * OAuth 결과는 여기서 소비되므로 같은 인증 결과로 여러 서버를 연결할 수 없다.
     */
    public DiscordBotInstallStartResponse startInstallation(
            Long communityId,
            String oauthResultId,
            String guildId
    ) {
        // 클라이언트가 보낸 guildId를 신뢰하지 않고 OAuth에서 조회한 서버 목록과 대조한다.
        DiscordManageableGuildResponse selectedGuild = oauthSessionStore.consumeSelectedGuild(
                communityId,
                oauthResultId,
                guildId
        );

        // 봇이 이미 서버에 있으면 초대 화면 없이 연결하고, 없으면 전용 초대 URL을 만든다.
        return discordGuildService.findGuildById(selectedGuild.id())
                .map(guild -> connectAlreadyInstalledGuild(communityId, guild))
                .orElseGet(() -> createBotAuthorization(communityId, selectedGuild));
    }

    /** 설치 토큰을 검증하고 JDA에서 봇 참여를 확인한 뒤 실제 연결 정보를 저장한다. */
    public DiscordBotInstallConfirmResponse confirmInstallation(String installToken) {
        DiscordBotInstallSession installSession = botInstallStore.getInstallSession(installToken);
        Guild guild = awaitGuild(installSession.guildId());
        connectionService.connect(
                installSession.communityId(),
                installSession.guildId(),
                installSession.guildName()
        );
        // 성공한 토큰은 다시 사용할 수 없게 제거한다.
        botInstallStore.removeInstallToken(installToken);
        return connectedResponse(installSession.communityId(), installSession.guildName());
    }

    /** 봇이 이미 참여한 서버를 커뮤니티와 즉시 연결한다. */
    private DiscordBotInstallStartResponse connectAlreadyInstalledGuild(Long communityId, Guild guild) {
        connectionService.connect(communityId, guild.getId(), guild.getName());
        return new DiscordBotInstallStartResponse(true, null, null, communityId, guild.getName());
    }

    /** 선택 서버를 고정한 Discord 봇 초대 URL과 설치 확인용 토큰을 만든다. */
    private DiscordBotInstallStartResponse createBotAuthorization(
            Long communityId,
            DiscordManageableGuildResponse selectedGuild
    ) {
        properties.validateBotInstall();
        String installToken = botInstallStore.createInstallToken(new DiscordBotInstallSession(
                communityId,
                selectedGuild.id(),
                selectedGuild.name()
        ));
        // permissions=0은 설치 시 별도 서버 권한을 요청하지 않는다는 뜻이다.
        String authorizationUrl = UriComponentsBuilder.fromUriString(DISCORD_AUTHORIZE_URL)
                .queryParam("client_id", properties.clientId())
                .queryParam("scope", "bot")
                .queryParam("permissions", "0")
                .queryParam("guild_id", selectedGuild.id())
                .queryParam("disable_guild_select", "true")
                .build()
                .encode()
                .toUriString();
        return new DiscordBotInstallStartResponse(
                false,
                authorizationUrl,
                installToken,
                communityId,
                selectedGuild.name()
        );
    }

    /**
     * 봇 설치 직후 발생할 수 있는 JDA 캐시 반영 지연을 고려해 서버 존재 여부를 재확인한다.
     */
    private Guild awaitGuild(String guildId) {
        for (int attempt = 1; attempt <= guildCheckAttempts; attempt++) {
            Guild guild = discordGuildService.findGuildById(guildId).orElse(null);
            if (guild != null) {
                return guild;
            }
            if (attempt < guildCheckAttempts) {
                waitBeforeRetry(guildId);
            }
        }
        throw new DiscordBotNotInstalledException(guildId);
    }

    /** 재시도 사이에 잠시 대기하고, 스레드 중단 요청이 오면 중단 상태를 보존한다. */
    private void waitBeforeRetry(String guildId) {
        try {
            Thread.sleep(guildCheckDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DiscordBotNotInstalledException(guildId);
        }
    }

    /** 연결 완료 화면에 필요한 공통 응답을 만든다. */
    private DiscordBotInstallConfirmResponse connectedResponse(Long communityId, String guildName) {
        return new DiscordBotInstallConfirmResponse(true, communityId, guildName);
    }
}
