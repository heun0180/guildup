package com.guildup.community.controller;

import com.guildup.community.service.CommunityDiscordQueryService;
import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 연결된 커뮤니티 ID를 기준으로 Discord 역할과 역할별 멤버를 조회하는 API다. */
@RestController
@RequestMapping("/api/communities/{communityId}/discord/roles")
public class CommunityDiscordController {

    private final CommunityDiscordQueryService communityDiscordQueryService;

    public CommunityDiscordController(CommunityDiscordQueryService communityDiscordQueryService) {
        this.communityDiscordQueryService = communityDiscordQueryService;
    }

    /** 커뮤니티에 연결된 Discord 서버의 역할 목록을 반환한다. */
    @GetMapping
    public List<DiscordRoleResponse> getRoles(@PathVariable Long communityId) {
        return communityDiscordQueryService.getRoles(communityId);
    }

    /** 커뮤니티에 연결된 서버에서 특정 Discord 역할을 가진 멤버를 반환한다. */
    @GetMapping("/{roleId}/members")
    public List<DiscordMemberResponse> getMembers(
            @PathVariable Long communityId,
            @PathVariable String roleId
    ) {
        return communityDiscordQueryService.getMembers(communityId, roleId);
    }
}
