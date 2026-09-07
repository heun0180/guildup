package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMember;

/** 내부 엔티티 대신 화면에 공개할 클랜원 필드만 담는 응답이다. */
public record CommunityMemberResponse(
        Long id,
        String nickname
) {

    /** CommunityMember 엔티티를 API 응답으로 변환한다. */
    public static CommunityMemberResponse from(CommunityMember member) {
        return new CommunityMemberResponse(member.getId(), member.getNickname());
    }
}
