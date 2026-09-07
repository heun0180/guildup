package com.guildup.community.dto;

import com.guildup.community.domain.GameType;

/** 커뮤니티 생성 요청이다. */
public record CommunityCreateRequest(String name, GameType gameType) {
}
