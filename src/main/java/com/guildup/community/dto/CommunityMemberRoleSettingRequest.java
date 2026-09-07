package com.guildup.community.dto;

import java.util.List;

/** 클랜원으로 판별할 Discord 역할 ID 목록을 받는 요청이다. */
public record CommunityMemberRoleSettingRequest(List<String> discordRoleIds) {
}
