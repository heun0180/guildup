package com.guildup.community.controller;

import com.guildup.community.domain.Community;
import com.guildup.community.dto.CommunityCreateRequest;
import com.guildup.community.dto.CommunityDiscordMemberRoleRequest;
import com.guildup.community.service.CommunityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @PostMapping
    public ResponseEntity<Community> createCommunity(@RequestBody CommunityCreateRequest request) {
        Community community = communityService.createCommunity(
                request.name(),
                request.discordGuildId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(community);
    }

    @PutMapping("/{communityId}/discord-member-role")
    public Community configureDiscordMemberRole(
            @PathVariable Long communityId,
            @RequestBody CommunityDiscordMemberRoleRequest request
    ) {
        return communityService.configureDiscordMemberRole(communityId, request.roleId());
    }
}
