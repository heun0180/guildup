package com.guildup.community.activity;

public record ClanActivityPlayer(
        String accountId,
        String nickname,
        boolean clanMember,
        Long communityMemberId
) {
}
