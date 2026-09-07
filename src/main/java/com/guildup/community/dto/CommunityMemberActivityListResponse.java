package com.guildup.community.dto;

import com.guildup.community.activity.ClanActivityStatus;

import java.util.List;

public record CommunityMemberActivityListResponse(
        int totalMembers,
        int activeMembers,
        int noClanActivityMembers,
        int noRecentMatchMembers,
        int accountVerificationRequiredMembers,
        MemberActivityRuleResponse rule,
        CommunityActivitySyncResponse sync,
        List<MemberActivitySummaryResponse> members
) {
    public static CommunityMemberActivityListResponse of(
            MemberActivityRuleResponse rule,
            CommunityActivitySyncResponse sync,
            List<MemberActivitySummaryResponse> members
    ) {
        return new CommunityMemberActivityListResponse(
                members.size(),
                count(members, ClanActivityStatus.ACTIVE),
                count(members, ClanActivityStatus.NO_CLAN_ACTIVITY),
                count(members, ClanActivityStatus.NO_RECENT_MATCHES),
                count(members, ClanActivityStatus.ACCOUNT_VERIFICATION_REQUIRED),
                rule,
                sync,
                List.copyOf(members)
        );
    }

    private static int count(List<MemberActivitySummaryResponse> members, ClanActivityStatus status) {
        return (int) members.stream().filter(member -> member.status() == status).count();
    }
}
