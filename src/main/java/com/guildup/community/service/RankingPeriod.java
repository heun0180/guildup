package com.guildup.community.service;

import com.guildup.community.domain.RankingPeriodType;
import com.guildup.community.dto.RankingPeriodResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;

/** 기간 경계는 JVM 기본 시간대와 무관하게 서울 달력에서 계산한다. */
public record RankingPeriod(RankingPeriodType type, LocalDate start, LocalDate end, boolean current) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static RankingPeriod resolve(RankingPeriodType type, Integer year, Integer month,
                                        Integer quarter, Clock clock) {
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        if (type == RankingPeriodType.ALL_TIME) {
            if (year != null || month != null || quarter != null) invalid();
            return new RankingPeriod(type, null, null, true);
        }
        boolean specified = year != null || month != null || quarter != null;
        if (specified && (year == null || year < 1 || year > 9999)) invalid();
        LocalDate start;
        LocalDate currentStart;
        if (type == RankingPeriodType.MONTHLY) {
            if (quarter != null || (specified && (month == null || month < 1 || month > 12))) invalid();
            currentStart = today.withDayOfMonth(1);
            start = specified ? LocalDate.of(year, month, 1) : currentStart;
        } else {
            if (month != null || (specified && (quarter == null || quarter < 1 || quarter > 4))) invalid();
            currentStart = LocalDate.of(today.getYear(), ((today.getMonthValue() - 1) / 3) * 3 + 1, 1);
            start = specified ? LocalDate.of(year, (quarter - 1) * 3 + 1, 1) : currentStart;
        }
        if (start.isAfter(currentStart)) invalid();
        return new RankingPeriod(type, start, start.plusMonths(type == RankingPeriodType.MONTHLY ? 1 : 3),
                start.equals(currentStart));
    }

    public Instant startInstant() { return start.atStartOfDay(ZONE).toInstant(); }
    public Instant endInstant() { return end.atStartOfDay(ZONE).toInstant(); }

    public RankingPeriodResponse response() {
        if (type == RankingPeriodType.ALL_TIME) {
            return new RankingPeriodResponse(type, null, null, null, null, null, "전체 랭킹", true);
        }
        Integer month = type == RankingPeriodType.MONTHLY ? start.getMonthValue() : null;
        Integer quarter = type == RankingPeriodType.QUARTERLY ? (start.getMonthValue() - 1) / 3 + 1 : null;
        String title = start.getYear() + "년 " + (month != null ? month + "월" : quarter + "분기") + " 랭킹";
        return new RankingPeriodResponse(type, start.getYear(), month, quarter, start, end.minusDays(1), title, current);
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "커뮤니티 집계 주기에 맞는 현재 또는 과거 기간을 선택해 주세요.");
    }
}
