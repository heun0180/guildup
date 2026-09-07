package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;

import java.time.Instant;

/** 내부 엔티티 대신 화면에 공개할 클랜원 필드만 담는 응답이다. */
public record CommunityMemberResponse(
        Long id,
        String nickname,
        String discordUserId,
        String discordUsername,
        String discordDisplayName,
        CommunityMemberStatus status,
        Instant discordJoinedAt,
        Instant createdAt,
        Instant updatedAt
) {

    /** CommunityMember 엔티티를 API 응답으로 변환한다. */
    public static CommunityMemberResponse from(
            CommunityMember member,
            CommunityMemberAccount discordAccount
    ) {
        return new CommunityMemberResponse(
                member.getId(),
                member.getNickname(),
                discordAccount == null ? null : discordAccount.getExternalUserId(),
                discordAccount == null ? null : discordAccount.getExternalUsername(),
                discordAccount == null ? null : discordAccount.getExternalDisplayName(),
                member.getStatus(),
                discordAccount == null ? null : discordAccount.getExternalJoinedAt(),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}
