package com.guildup.community.controller;

import com.guildup.community.dto.CommunityNewsSummaryResponse;
import com.guildup.community.service.CommunityNewsSummaryService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/communities/{communityId}/news-summary")
public class CommunityNewsSummaryController {
    private final CommunityNewsSummaryService service;

    public CommunityNewsSummaryController(CommunityNewsSummaryService service) { this.service = service; }

    @GetMapping
    public CommunityNewsSummaryResponse get(@PathVariable Long communityId, HttpSession session) {
        return service.get(CurrentUserSession.requireUserId(session), communityId);
    }
}
