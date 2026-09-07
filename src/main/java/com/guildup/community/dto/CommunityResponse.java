package com.guildup.community.dto;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.GameType;

/** Community 생성 및 목록 조회 결과에 사용할 응답 DTO다. */
public record CommunityResponse(
        Long id,
        String name,
        GameType gameType,
        String gameName
) {

    /** Community 엔티티를 외부 응답으로 변환한다. */
    public static CommunityResponse from(Community community, GameType gameType) {
        return new CommunityResponse(
                community.getId(), community.getName(), gameType, gameType.getDisplayName()
        );
    }
}
