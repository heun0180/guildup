package com.guildup.community.controller;

import com.guildup.community.dto.CommunityGameActivityRuleRequest;
import com.guildup.community.dto.CommunityGameActivityRuleResponse;
import com.guildup.community.service.CommunityGameActivityRuleService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티의 게임 활동 규칙 조회 및 변경 API다. */
@RestController
@RequestMapping("/api/communities/{communityId}/activity-rule")
public class CommunityGameActivityRuleController {

    private final CommunityGameActivityRuleService ruleService;

    public CommunityGameActivityRuleController(CommunityGameActivityRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public CommunityGameActivityRuleResponse getRule(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return ruleService.getRule(CurrentUserSession.requireUserId(session), communityId);
    }

    @PutMapping
    public CommunityGameActivityRuleResponse updateRule(
            @PathVariable Long communityId,
            @RequestBody CommunityGameActivityRuleRequest request,
            HttpSession session
    ) {
        return ruleService.updateRule(
                CurrentUserSession.requireUserId(session),
                communityId,
                request.activityPeriodDays(),
                request.minimumClanMembersInRoster()
        );
    }
}
