package com.guildup.community.dto;

import java.time.LocalDate;

public record AttendanceStatusResponse(boolean attended, LocalDate attendanceDate, int currentScore) {}
