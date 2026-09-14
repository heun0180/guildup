package com.guildup.community.service;

import com.guildup.community.domain.CommunityAttendance;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityScoreReferenceType;
import com.guildup.community.domain.CommunityScoreType;
import com.guildup.community.dto.AttendanceCheckResponse;
import com.guildup.community.dto.AttendanceStatusResponse;
import com.guildup.community.repository.CommunityAttendanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class CommunityAttendanceService {
    static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Seoul");

    private final CurrentCommunityMemberService currentMembers;
    private final CommunityAttendanceRepository attendances;
    private final CommunityScoreService scores;
    private final Clock clock;

    public CommunityAttendanceService(CurrentCommunityMemberService currentMembers,
                                      CommunityAttendanceRepository attendances,
                                      CommunityScoreService scores,
                                      Clock clock) {
        this.currentMembers = currentMembers;
        this.attendances = attendances;
        this.scores = scores;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttendanceStatusResponse getTodayStatus(Long userId, Long communityId) {
        CommunityMember member = currentMembers.require(userId, communityId);
        LocalDate today = today();
        var attendance = attendances.findByCommunityMemberIdAndAttendanceDate(member.getId(), today);
        return new AttendanceStatusResponse(
                attendance.isPresent(),
                attendance.map(CommunityAttendance::getAttendanceDate).orElse(null),
                scores.getTotalScore(member)
        );
    }

    /** 멤버 행 잠금 안에서 출석, 원장, 총점을 하나의 트랜잭션으로 반영한다. */
    @Transactional
    public AttendanceCheckResponse attend(Long userId, Long communityId) {
        CommunityMember member = currentMembers.requireForUpdate(userId, communityId);
        LocalDate today = today();
        var existing = attendances.findByCommunityMemberIdAndAttendanceDate(member.getId(), today);
        if (existing.isPresent()) {
            return new AttendanceCheckResponse(true, today, 0, scores.getTotalScore(member));
        }

        var attendance = attendances.saveAndFlush(new CommunityAttendance(member, today, clock.instant()));
        int total = scores.addScore(
                member,
                CommunityScoreType.ATTENDANCE,
                1,
                "일일 출석",
                CommunityScoreReferenceType.ATTENDANCE,
                attendance.getId(),
                clock.instant()
        );
        return new AttendanceCheckResponse(true, today, 1, total);
    }

    private LocalDate today() {
        return clock.instant().atZone(ATTENDANCE_ZONE).toLocalDate();
    }
}
