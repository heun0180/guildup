package com.guildup.discord.oauth.dto;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

/** Discord 관리 권한 검증 후 기존 GuildUp 커뮤니티에 참여한 결과다. */
public record CommunityJoinResponse(
        Long communityId,
        String communityName,
        CommunityUserRole role
) {
    public static CommunityJoinResponse from(CommunityUser membership) {
        return new CommunityJoinResponse(
                membership.getCommunity().getId(),
                membership.getCommunity().getName(),
                membership.getRole()
        );
    }
}
