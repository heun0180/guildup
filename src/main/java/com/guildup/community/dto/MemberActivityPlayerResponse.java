package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberActivityMatchPlayer;

public record MemberActivityPlayerResponse(
        String pubgAccountId,
        String pubgNickname,
        boolean clanMember,
        Long communityMemberId
) {
    public static MemberActivityPlayerResponse from(CommunityMemberActivityMatchPlayer player) {
        return new MemberActivityPlayerResponse(
                player.getPubgAccountId(),
                player.getPubgNickname(),
                player.isClanMember(),
                player.getCommunityMember() == null ? null : player.getCommunityMember().getId()
        );
    }
}
