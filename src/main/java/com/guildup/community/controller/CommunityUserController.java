package com.guildup.community.controller;

import com.guildup.community.dto.CommunityUserResponse;
import com.guildup.community.dto.CommunityUserRoleUpdateRequest;
import com.guildup.community.service.CommunityMembershipService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/users")
public class CommunityUserController {
    private final CommunityMembershipService memberships;

    public CommunityUserController(CommunityMembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    public List<CommunityUserResponse> list(@PathVariable Long communityId, HttpSession session) {
        return memberships.getCommunityUsers(CurrentUserSession.requireUserId(session), communityId);
    }

    @PatchMapping("/{userId}/role")
    public CommunityUserResponse changeRole(
            @PathVariable Long communityId,
            @PathVariable Long userId,
            @RequestBody CommunityUserRoleUpdateRequest request,
            HttpSession session
    ) {
        return memberships.changeRole(
                CurrentUserSession.requireUserId(session), communityId, userId, request.role()
        );
    }
}
