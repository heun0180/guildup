package com.guildup.community.controller;

import com.guildup.community.dto.CommunityMemberRoleSettingRequest;
import com.guildup.community.dto.CommunityMemberRoleSettingsResponse;
import com.guildup.community.service.CommunityMemberRoleSettingService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티의 클랜원 판별 Discord 역할 설정 API다. */
@RestController
@RequestMapping("/api/communities/{communityId}/member-role-settings")
public class CommunityMemberRoleSettingController {

    private final CommunityMemberRoleSettingService settingService;

    public CommunityMemberRoleSettingController(CommunityMemberRoleSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    public CommunityMemberRoleSettingsResponse getSettings(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return settingService.getSettings(CurrentUserSession.requireUserId(session), communityId);
    }

    @PutMapping
    public CommunityMemberRoleSettingsResponse updateSettings(
            @PathVariable Long communityId,
            @RequestBody CommunityMemberRoleSettingRequest request,
            HttpSession session
    ) {
        return settingService.updateSettings(
                CurrentUserSession.requireUserId(session),
                communityId,
                request.discordRoleIds()
        );
    }
}
