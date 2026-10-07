package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.pubg.model.PubgPlatform;

public record PubgAccountResponse(PubgPlatform platform, String nickname, String accountId) {
    public static PubgAccountResponse from(CommunityMemberAccount account) {
        if (account.getCommunityMember().isAnonymized()) return new PubgAccountResponse(account.getPlatform(), null, null);
        return new PubgAccountResponse(account.getPlatform(), account.getExternalUsername(), account.getExternalUserId());
    }
}
