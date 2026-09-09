package com.guildup.community.controller;

import com.guildup.community.service.CommunityDiscordDmService;
import com.guildup.discord.dto.DiscordDmRequest;
import com.guildup.discord.dto.DiscordDmRecipientsResponse;
import com.guildup.discord.dto.DiscordDmResponse;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티 운영진의 Discord DM 수신자 조회와 발송 API다. */
@RestController
@RequestMapping("/api/communities/{communityId}/discord/dm")
public class CommunityDiscordDmController {

    private final CommunityDiscordDmService service;

    public CommunityDiscordDmController(CommunityDiscordDmService service) {
        this.service = service;
    }

    @GetMapping("/recipients")
    public DiscordDmRecipientsResponse getRecipients(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return service.getRecipients(CurrentUserSession.requireUserId(session), communityId);
    }

    @PostMapping
    public DiscordDmResponse send(
            @PathVariable Long communityId,
            @RequestBody DiscordDmRequest request,
            HttpSession session
    ) {
        return service.send(CurrentUserSession.requireUserId(session), communityId, request);
    }
}
