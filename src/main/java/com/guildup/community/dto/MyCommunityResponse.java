package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

public record MyCommunityResponse(Long id, String name, CommunityUserRole role) {
    public static MyCommunityResponse from(CommunityUser membership) {
        return new MyCommunityResponse(membership.getCommunity().getId(),
                membership.getCommunity().getName(), membership.getRole());
    }
}
