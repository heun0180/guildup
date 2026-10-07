package com.guildup.community.service;

import com.guildup.discord.domain.DiscordVoiceActivityPeriod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

/** 과거 기간은 달력상의 끝까지, 현재 기간은 현재 시각까지 조회한다. */
public record DiscordVoiceActivityRange(Instant start, Instant end, boolean current) {
    public static DiscordVoiceActivityRange resolve(
            DiscordVoiceActivityPeriod period, LocalDate referenceDate, Instant now
    ) {
        if (period == DiscordVoiceActivityPeriod.ALL) {
            if (referenceDate != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전체 조회에는 기준 날짜를 지정할 수 없습니다.");
            }
            return new DiscordVoiceActivityRange(null, now, true);
        }
        LocalDate today = now.atZone(DiscordVoiceActivityPeriod.ZONE).toLocalDate();
        LocalDate selected = referenceDate == null ? today : referenceDate;
        if (selected.getYear() < 1 || selected.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기준 날짜의 연도는 1~9999 범위여야 합니다.");
        }
        LocalDate startDate = period.startDate(selected);
        LocalDate currentStart = period.startDate(today);
        if (startDate.isAfter(currentStart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "미래 기간은 조회할 수 없습니다.");
        }
        boolean current = startDate.equals(currentStart);
        Instant start = startDate.atStartOfDay(DiscordVoiceActivityPeriod.ZONE).toInstant();
        Instant end = current ? now : period.endDate(selected).atStartOfDay(DiscordVoiceActivityPeriod.ZONE).toInstant();
        return new DiscordVoiceActivityRange(start, end, current);
    }
}
