package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberRoleSetting;

import java.util.List;

/** 커뮤니티의 클랜원 판별 Discord 역할 설정 응답이다. */
public record CommunityMemberRoleSettingsResponse(
        List<CommunityMemberRoleSettingRoleResponse> roles
) {
    public static CommunityMemberRoleSettingsResponse from(List<CommunityMemberRoleSetting> settings) {
        return new CommunityMemberRoleSettingsResponse(settings.stream()
                .map(CommunityMemberRoleSettingRoleResponse::from)
                .toList());
    }
}
