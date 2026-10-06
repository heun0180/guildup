package com.guildup.discord.oauth.controller;

import com.guildup.community.service.CommunityMembershipService;
import com.guildup.discord.oauth.dto.CommunityJoinResponse;
import com.guildup.discord.oauth.dto.DiscordGuildSelectionRequest;
import com.guildup.discord.oauth.dto.DiscordGuildSelectionResponse;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** OAuth로 확인한 Discord 서버의 기존 커뮤니티 조회와 관리자 참여 API다. */
@RestController
public class DiscordCommunityMembershipController {
    private final CommunityMembershipService memberships;

    public DiscordCommunityMembershipController(CommunityMembershipService memberships) {
        this.memberships = memberships;
    }

    @PostMapping("/api/communities/{communityId}/discord/guild-selection/inspect")
    public DiscordGuildSelectionResponse inspect(
            @PathVariable Long communityId,
            @RequestBody DiscordGuildSelectionRequest request,
            HttpSession session
    ) {
        return memberships.inspect(
                CurrentUserSession.requireUserId(session), communityId,
                request.oauthResultId(), request.guildId()
        );
    }

    @PostMapping("/api/communities/{communityId}/discord/guild-selection/join")
    public CommunityJoinResponse join(
            @PathVariable Long communityId,
            @RequestBody DiscordGuildSelectionRequest request,
            HttpSession session
    ) {
        return memberships.join(
                CurrentUserSession.requireUserId(session), communityId,
                request.oauthResultId(), request.guildId(), request.shouldDiscardSourceCommunity()
        );
    }

    @PostMapping("/api/community-creation/discord/guild-selection/inspect")
    public DiscordGuildSelectionResponse inspectCreation(@RequestBody DiscordGuildSelectionRequest request,
                                                         HttpSession session) {
        DiscordOAuthController.requireCreationResult(session, request.oauthResultId());
        return memberships.inspectCreation(CurrentUserSession.requireUserId(session), request.oauthResultId(), request.guildId());
    }
}
