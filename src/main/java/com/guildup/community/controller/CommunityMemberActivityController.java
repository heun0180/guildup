package com.guildup.community.controller;

import com.guildup.community.dto.CommunityMemberActivityDetailResponse;
import com.guildup.community.dto.CommunityMemberActivityListResponse;
import com.guildup.community.service.CommunityMemberActivityService;
import com.guildup.community.service.CommunityMemberActivitySyncService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 클랜원 활동 요약 목록과 한 클랜원의 경기 상세를 제공한다. */
@RestController
@RequestMapping("/api/communities/{communityId}")
public class CommunityMemberActivityController {

    private final CommunityMemberActivityService activityService;
    private final CommunityMemberActivitySyncService syncService;

    public CommunityMemberActivityController(
            CommunityMemberActivityService activityService,
            CommunityMemberActivitySyncService syncService
    ) {
        this.activityService = activityService;
        this.syncService = syncService;
    }

    @PostMapping("/member-activities/sync")
    public CommunityMemberActivityListResponse syncActivities(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return syncService.sync(
                CurrentUserSession.requireUserId(session), communityId
        );
    }

    @GetMapping("/member-activities")
    public CommunityMemberActivityListResponse getActivities(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return activityService.getActivities(
                CurrentUserSession.requireUserId(session), communityId
        );
    }

    @GetMapping("/members/{memberId}/activity")
    public CommunityMemberActivityDetailResponse getActivity(
            @PathVariable Long communityId,
            @PathVariable Long memberId,
            HttpSession session
    ) {
        return activityService.getActivity(
                CurrentUserSession.requireUserId(session), communityId, memberId
        );
    }
}
