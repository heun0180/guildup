package com.guildup.community.controller;

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
    public CommunityRankingsResponse getRankings(@PathVariable Long communityId, HttpSession session) {
        return rankings.getRankings(CurrentUserSession.requireUserId(session), communityId);
    }
}
