package com.guildup.community.controller;

import com.guildup.community.dto.RankingSettingsResponse;
import com.guildup.community.dto.RankingSettingsRequest;
import com.guildup.community.dto.AttendanceCheckResponse;
import com.guildup.community.dto.AttendanceStatusResponse;
import com.guildup.community.dto.CommunityRankingsResponse;
import com.guildup.community.service.CommunityAttendanceService;
import com.guildup.community.service.CommunityRankingService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/communities/{communityId}")
public class CommunityScoreController {
    private final CommunityAttendanceService attendance;
    private final CommunityRankingService rankings;

    public CommunityScoreController(CommunityAttendanceService attendance,
                                    CommunityRankingService rankings) {
        this.attendance = attendance;
        this.rankings = rankings;
    }

    @GetMapping("/attendance/me")
    public AttendanceStatusResponse getMyAttendance(@PathVariable Long communityId, HttpSession session) {
        return attendance.getTodayStatus(CurrentUserSession.requireUserId(session), communityId);
    }

    @PostMapping("/attendance")
    public AttendanceCheckResponse attend(@PathVariable Long communityId, HttpSession session) {
        return attendance.attend(CurrentUserSession.requireUserId(session), communityId);
    }

    @GetMapping("/rankings")
    public CommunityRankingsResponse getRankings(@PathVariable Long communityId, HttpSession session,
                                                 @RequestParam(required = false) Integer year,
                                                 @RequestParam(required = false) Integer month,
                                                 @RequestParam(required = false) Integer quarter) {
        return rankings.getRankings(CurrentUserSession.requireUserId(session), communityId, year, month, quarter);
    }

    @GetMapping("/ranking-settings")
    public RankingSettingsResponse getSettings(@PathVariable Long communityId, HttpSession session) {
        return rankings.getSettings(CurrentUserSession.requireUserId(session), communityId);
    }

    @PutMapping("/ranking-settings")
    public RankingSettingsResponse updateSettings(@PathVariable Long communityId, HttpSession session,
                                                  @RequestBody RankingSettingsRequest request) {
        return rankings.updateSettings(CurrentUserSession.requireUserId(session), communityId, request.periodType());
    }
}
