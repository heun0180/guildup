package com.guildup.discord.oauth.controller;

import com.guildup.discord.oauth.dto.DiscordBotInstallConfirmRequest;
import com.guildup.discord.oauth.dto.DiscordBotInstallConfirmResponse;
import com.guildup.discord.oauth.dto.DiscordBotInstallStartRequest;
import com.guildup.discord.oauth.dto.DiscordBotInstallStartResponse;
import com.guildup.discord.oauth.service.DiscordBotInstallService;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.servlet.http.HttpSession;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.discord.oauth.store.DiscordBotInstallStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** OAuth로 선택한 Discord 서버에 GuildUp 봇을 설치하고 연결을 확정하는 API다. */
@RestController
public class DiscordBotInstallController {

    private final DiscordBotInstallService botInstallService;

    private final DiscordBotInstallStore installStore;
    private final CommunityAccessService access;

    public DiscordBotInstallController(DiscordBotInstallService botInstallService,
                                       DiscordBotInstallStore installStore, CommunityAccessService access) {
        this.installStore = installStore;
        this.access = access;
        this.botInstallService = botInstallService;
    }

    /**
     * 선택한 서버가 OAuth 결과에 포함된 서버인지 검증하고 봇 설치 단계를 시작한다.
     * 이미 봇이 들어가 있으면 즉시 연결하고, 아니면 Discord 봇 초대 URL을 반환한다.
     */
    @PostMapping("/api/communities/{communityId}/discord/bot-install/authorize")
    public DiscordBotInstallStartResponse authorize(
            @PathVariable Long communityId,
            @RequestBody DiscordBotInstallStartRequest request
    ) {
        return botInstallService.startInstallation(
                communityId,
                request.oauthResultId(),
                request.guildId()
        );
    }

    /**
     * 사용자가 Discord 설치 화면을 마친 뒤 호출한다.
     * JDA에서 해당 서버가 확인되면 커뮤니티와 Discord 서버의 연결을 저장한다.
     */
    @PostMapping("/api/discord/bot-install/confirm")
    public DiscordBotInstallConfirmResponse confirm(
            @RequestBody DiscordBotInstallConfirmRequest request,
            HttpSession session
    ) {
        Long userId = CurrentUserSession.requireUserId(session);
        var installation = installStore.getInstallSession(request.installToken());
        access.requireAccess(userId, installation.communityId());
        return botInstallService.confirmInstallation(request.installToken());
    }
}
