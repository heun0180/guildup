package com.guildup.discord.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** 음성 활동 조회의 시작 경계를 서울 달력 기준으로 계산한다. ALL은 시작 제한이 없다. */
public enum DiscordVoiceActivityPeriod {
    ALL, DAY, WEEK, MONTH, YEAR;

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public Instant startAt(Instant queryEnd) {
        LocalDate start = startDate(queryEnd.atZone(ZONE).toLocalDate());
        return start == null ? null : start.atStartOfDay(ZONE).toInstant();
    }

    public LocalDate startDate(LocalDate referenceDate) {
        return switch (this) {
            case ALL -> null;
            case DAY -> referenceDate;
            case WEEK -> referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> referenceDate.withDayOfMonth(1);
            case YEAR -> referenceDate.withDayOfYear(1);
        };
    }

    public LocalDate endDate(LocalDate referenceDate) {
        LocalDate start = startDate(referenceDate);
        return switch (this) {
            case ALL -> null;
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
        };
    }
}
