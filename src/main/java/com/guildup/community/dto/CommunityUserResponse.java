package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

public record CommunityUserResponse(Long userId, String nickname, CommunityUserRole role) {
    public static CommunityUserResponse from(CommunityUser membership) {
        return new CommunityUserResponse(
                membership.getUser().getId(), membership.getUser().getNickname(), membership.getRole()
        );
    }
}
