package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberRoleSetting;

/** 저장된 Discord 역할 ID와 표시 이름이다. */
public record CommunityMemberRoleSettingRoleResponse(
        String discordRoleId,
        String discordRoleName
) {
    public static CommunityMemberRoleSettingRoleResponse from(CommunityMemberRoleSetting setting) {
        return new CommunityMemberRoleSettingRoleResponse(
                setting.getDiscordRoleId(),
                setting.getDiscordRoleName()
        );
    }
}
