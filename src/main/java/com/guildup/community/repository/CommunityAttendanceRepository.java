package com.guildup.community.repository;

import com.guildup.community.domain.CommunityAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CommunityAttendanceRepository extends JpaRepository<CommunityAttendance, Long> {
    Optional<CommunityAttendance> findByCommunityMemberIdAndAttendanceDate(Long memberId, LocalDate date);
    long countByCommunityMemberIdAndAttendanceDate(Long memberId, LocalDate date);
}
