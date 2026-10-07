package com.guildup.community.controller;

import com.guildup.community.service.CommunityDiscordVoiceActivityService;
import com.guildup.discord.domain.DiscordVoiceActivityPeriod;
import com.guildup.discord.dto.DiscordVoiceActivityDetailResponse;
import com.guildup.discord.dto.DiscordVoiceActivitySummaryResponse;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDate;

/** 커뮤니티 운영진에게 저장된 Discord 음성 활동을 제공한다. */
@RestController
@RequestMapping("/api/communities/{communityId}/discord/voice-activity")
public class CommunityDiscordVoiceActivityController {

    private final CommunityDiscordVoiceActivityService service;

    public CommunityDiscordVoiceActivityController(CommunityDiscordVoiceActivityService service) {
        this.service = service;
    }

    @GetMapping
    public List<DiscordVoiceActivitySummaryResponse> getSummaries(
            @PathVariable Long communityId,
            @RequestParam(defaultValue = "ALL") DiscordVoiceActivityPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            HttpSession session
    ) {
        return service.getSummaries(CurrentUserSession.requireUserId(session), communityId, period, referenceDate);
    }

    @GetMapping("/members/{communityMemberId}")
    public DiscordVoiceActivityDetailResponse getDetail(
            @PathVariable Long communityId,
            @PathVariable Long communityMemberId,
            @RequestParam(defaultValue = "ALL") DiscordVoiceActivityPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            HttpSession session
    ) {
        return service.getDetail(
                CurrentUserSession.requireUserId(session), communityId, communityMemberId, period, referenceDate
        );
    }
}
