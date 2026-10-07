package com.guildup.community.service;

import com.guildup.discord.domain.DiscordVoiceActivityPeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordVoiceActivityRangeTests {
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "DAY, 2026-10-05, 2026-10-04T15:00:00Z, 2026-10-05T15:00:00Z",
            "WEEK, 2026-10-01, 2026-09-27T15:00:00Z, 2026-10-04T15:00:00Z",
            "MONTH, 2026-09-15, 2026-08-31T15:00:00Z, 2026-09-30T15:00:00Z",
            "YEAR, 2025-06-15, 2024-12-31T15:00:00Z, 2025-12-31T15:00:00Z",
            "MONTH, 2024-02-29, 2024-01-31T15:00:00Z, 2024-02-29T15:00:00Z",
            "WEEK, 2026-01-01, 2025-12-28T15:00:00Z, 2026-01-04T15:00:00Z"
    })
    void pastPeriodsUseTheirFullSeoulCalendarRange(
            DiscordVoiceActivityPeriod period, LocalDate referenceDate, Instant start, Instant end
    ) {
        assertThat(DiscordVoiceActivityRange.resolve(period, referenceDate, NOW))
                .isEqualTo(new DiscordVoiceActivityRange(start, end, false));
    }

    @ParameterizedTest
    @EnumSource(value = DiscordVoiceActivityPeriod.class, names = {"DAY", "WEEK", "MONTH", "YEAR"})
    void currentPeriodsAndPeriodOnlyRequestsEndAtNow(DiscordVoiceActivityPeriod period) {
        var legacy = DiscordVoiceActivityRange.resolve(period, null, NOW);
        var explicit = DiscordVoiceActivityRange.resolve(period, LocalDate.parse("2026-10-07"), NOW);
        assertThat(legacy).isEqualTo(explicit);
        assertThat(explicit.start()).isEqualTo(period.startAt(NOW));
        assertThat(explicit.end()).isEqualTo(NOW);
        assertThat(explicit.current()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"DAY, 2026-10-08", "WEEK, 2026-10-12", "MONTH, 2026-11-01", "YEAR, 2027-01-01"})
    void futurePeriodsAreRejected(DiscordVoiceActivityPeriod period, LocalDate referenceDate) {
        assertThatThrownBy(() -> DiscordVoiceActivityRange.resolve(period, referenceDate, NOW))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void referenceDayWithinCurrentWeekMonthOrYearResolvesToCurrentPeriod() {
        assertThat(DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.WEEK, LocalDate.parse("2026-10-11"), NOW).end()).isEqualTo(NOW);
        assertThat(DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.MONTH, LocalDate.parse("2026-10-31"), NOW).end()).isEqualTo(NOW);
        assertThat(DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.YEAR, LocalDate.parse("2026-12-31"), NOW).end()).isEqualTo(NOW);
    }

    @Test
    void allKeepsItsUnboundedStartAndRejectsUnusedReferenceDate() {
        assertThat(DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.ALL, null, NOW))
                .isEqualTo(new DiscordVoiceActivityRange(null, NOW, true));
        assertThatThrownBy(() -> DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.ALL, LocalDate.parse("2025-01-01"), NOW))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unsupportedYearIsRejected() {
        assertThatThrownBy(() -> DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.YEAR, LocalDate.of(0, 1, 1), NOW))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void exactSeoulMidnightStartsNewPeriodAndFinishesPreviousDay() {
        Instant midnight = Instant.parse("2026-10-06T15:00:00Z");
        var yesterday = DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.DAY, LocalDate.parse("2026-10-06"), midnight);
        var today = DiscordVoiceActivityRange.resolve(DiscordVoiceActivityPeriod.DAY, null, midnight);
        assertThat(yesterday.end()).isEqualTo(midnight);
        assertThat(today.start()).isEqualTo(midnight);
        assertThat(today.end()).isEqualTo(midnight);
    }
}
