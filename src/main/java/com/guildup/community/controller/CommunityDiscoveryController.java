package com.guildup.community.controller;

import com.guildup.community.dto.DiscoverableCommunityResponse;
import com.guildup.community.service.CommunityMembershipService;
import com.guildup.discord.oauth.dto.CommunityJoinResponse;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Discord는 Community 발견 수단으로만 사용하고 가입 결과는 community_users에 저장한다. */
@RestController
@RequestMapping("/api/community-discoveries/discord")
public class CommunityDiscoveryController {
    private final CommunityMembershipService memberships;

    public CommunityDiscoveryController(CommunityMembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    public List<DiscoverableCommunityResponse> discover(HttpSession session) {
        return memberships.discoverByDiscordMembership(CurrentUserSession.requireUserId(session));
    }

    @PostMapping("/{communityId}/join")
    public CommunityJoinResponse join(@PathVariable Long communityId, HttpSession session) {
        return memberships.joinDiscoveredCommunity(CurrentUserSession.requireUserId(session), communityId);
    }
}
