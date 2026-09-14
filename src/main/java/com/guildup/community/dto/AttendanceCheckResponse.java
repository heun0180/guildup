package com.guildup.community.dto;

import java.time.LocalDate;

public record AttendanceCheckResponse(
        boolean attended,
        LocalDate attendanceDate,
        int scoreAdded,
        int currentScore
) {}
