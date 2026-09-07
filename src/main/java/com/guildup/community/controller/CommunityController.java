package com.guildup.community.controller;

import com.guildup.community.domain.Community;
import com.guildup.community.dto.CommunityCreateRequest;
import com.guildup.community.dto.CommunityDiscordMemberRoleRequest;
import com.guildup.community.dto.CommunityResponse;
import com.guildup.community.service.CommunityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.servlet.http.HttpSession;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.community.dto.MyCommunityResponse;
import com.guildup.community.dto.CommunityDashboardResponse;

/** 커뮤니티 생성과 커뮤니티의 Discord 멤버 역할 설정 API를 제공한다. */
@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    /** 새 커뮤니티를 만들고 201 Created로 생성 결과를 반환한다. */
    @PostMapping
    public ResponseEntity<CommunityResponse> createCommunity(@RequestBody CommunityCreateRequest request, HttpSession session) {
        Community community = communityService.createCommunity(request.name(), CurrentUserSession.requireUserId(session));

        return ResponseEntity.status(HttpStatus.CREATED).body(CommunityResponse.from(community));
    }

    /** 기존 목록 경로도 현재 사용자의 Community만 반환한다. */
    @GetMapping
    public List<MyCommunityResponse> getCommunities(HttpSession session) {
        return communityService.getCommunities(CurrentUserSession.requireUserId(session));
    }

    @GetMapping("/{communityId}")
    public CommunityDashboardResponse getDashboard(@PathVariable Long communityId, HttpSession session) {
        return communityService.getDashboard(CurrentUserSession.requireUserId(session), communityId);
    }

    /** Discord 연결의 멤버 역할 ID를 설정한다. */
    @PutMapping("/{communityId}/discord-member-role")
    public Community configureDiscordMemberRole(
            @PathVariable Long communityId,
            @RequestBody CommunityDiscordMemberRoleRequest request
    ) {
        return communityService.configureDiscordMemberRole(communityId, request.roleId());
    }
}
