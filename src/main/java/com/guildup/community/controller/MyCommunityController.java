package com.guildup.community.controller;

import com.guildup.community.dto.MyCommunityResponse;
import com.guildup.community.service.CommunityService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class MyCommunityController {
    private final CommunityService service;
    public MyCommunityController(CommunityService service) { this.service = service; }
    @GetMapping("/api/auth/me/communities")
    public List<MyCommunityResponse> getCommunities(HttpSession session) {
        return service.getCommunities(CurrentUserSession.requireUserId(session));
    }
}
