package com.guildup.community.dto;

/** 클랜원을 닉네임으로 직접 추가하는 요청이다. */
public record CommunityMemberCreateRequest(String nickname) {
}
