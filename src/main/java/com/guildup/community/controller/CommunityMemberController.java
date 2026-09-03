package com.guildup.community.controller;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.dto.CommunityMemberCreateRequest;
import com.guildup.community.dto.CommunityMemberResponse;
import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.service.CommunityMemberService;
import com.guildup.community.service.DiscordCommunityMemberSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/members")
public class CommunityMemberController {

    private final CommunityMemberService communityMemberService;
    private final DiscordCommunityMemberSyncService discordCommunityMemberSyncService;

    public CommunityMemberController(
            CommunityMemberService communityMemberService,
            DiscordCommunityMemberSyncService discordCommunityMemberSyncService
    ) {
        this.communityMemberService = communityMemberService;
        this.discordCommunityMemberSyncService = discordCommunityMemberSyncService;
    }

    @PostMapping
    public ResponseEntity<CommunityMemberResponse> addMember(
            @PathVariable Long communityId,
            @RequestBody CommunityMemberCreateRequest request
    ) {
        CommunityMember member = communityMemberService.addMember(communityId, request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(CommunityMemberResponse.from(member));
    }

    @GetMapping
    public List<CommunityMemberResponse> getMembers(@PathVariable Long communityId) {
        return communityMemberService.getMembers(communityId).stream()
                .map(CommunityMemberResponse::from)
                .toList();
    }

    @PostMapping("/sync-discord")
    public CommunityMemberSyncResponse syncMembersFromDiscord(@PathVariable Long communityId) {
        DiscordCommunityMemberSyncService.SyncResult result =
                discordCommunityMemberSyncService.syncMembersFromDiscord(communityId);

        return new CommunityMemberSyncResponse(
                result.added(),
                result.updated(),
                result.removed(),
                result.total()
        );
    }
}
