package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMember;

public record CommunityMemberResponse(
        Long id,
        String nickname
) {

    public static CommunityMemberResponse from(CommunityMember member) {
        return new CommunityMemberResponse(member.getId(), member.getNickname());
    }
}
