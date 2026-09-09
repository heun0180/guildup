package com.guildup.community.controller;

import com.guildup.community.dto.TeamGenerationRequest;
import com.guildup.community.dto.TeamGenerationResponse;
import com.guildup.community.dto.TeamMakerParticipantsResponse;
import com.guildup.community.dto.TeamRebalanceRequest;
import com.guildup.community.service.CommunityTeamMakerService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/communities/{communityId}/team-maker")
public class CommunityTeamMakerController {
    private final CommunityTeamMakerService service;

    public CommunityTeamMakerController(CommunityTeamMakerService service) {
        this.service = service;
    }

    @GetMapping("/participants")
    public TeamMakerParticipantsResponse participants(@PathVariable Long communityId, HttpSession session) {
        return service.getParticipants(CurrentUserSession.requireUserId(session), communityId);
    }

    @PostMapping("/generate")
    public TeamGenerationResponse generate(
            @PathVariable Long communityId,
            @RequestBody TeamGenerationRequest request,
            HttpSession session
    ) {
        return service.generate(CurrentUserSession.requireUserId(session), communityId, request);
    }

    @PostMapping("/rebalance")
    public TeamGenerationResponse rebalance(
            @PathVariable Long communityId,
            @RequestBody TeamRebalanceRequest request,
            HttpSession session
    ) {
        return service.rebalance(CurrentUserSession.requireUserId(session), communityId, request);
    }
}
