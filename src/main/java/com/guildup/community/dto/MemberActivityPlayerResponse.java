package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberActivityMatchPlayer;

public record MemberActivityPlayerResponse(
        String pubgAccountId,
        String pubgNickname,
        boolean clanMember,
        Long communityMemberId
) {
    public static MemberActivityPlayerResponse from(CommunityMemberActivityMatchPlayer player) {
        boolean hidden = player.getCommunityMember() != null && player.getCommunityMember().isAnonymized();
        return new MemberActivityPlayerResponse(
                hidden ? null : player.getPubgAccountId(),
                hidden ? com.guildup.user.domain.User.WITHDRAWN_NAME : player.getPubgNickname(),
                player.isClanMember(),
                player.getCommunityMember() == null ? null : player.getCommunityMember().getId()
        );
    }
}
