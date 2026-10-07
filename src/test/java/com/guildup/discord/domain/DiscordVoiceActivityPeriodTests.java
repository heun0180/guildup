package com.guildup.discord.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordVoiceActivityPeriodTests {
    @ParameterizedTest
    @CsvSource({
            "DAY, 2026-10-06T15:00:00Z",
            "WEEK, 2026-10-04T15:00:00Z",
            "MONTH, 2026-09-30T15:00:00Z",
            "YEAR, 2025-12-31T15:00:00Z"
    })
    void calculatesStartFromSeoulCalendar(DiscordVoiceActivityPeriod period, Instant expected) {
        assertThat(period.startAt(Instant.parse("2026-10-07T03:00:00Z"))).isEqualTo(expected);
    }

    @Test
    void allHasNoStartBoundary() {
        assertThat(DiscordVoiceActivityPeriod.ALL.startAt(Instant.parse("2026-10-07T03:00:00Z"))).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "DAY, 2026-10-06T15:00:00Z, 2026-10-06T15:00:00Z",
            "WEEK, 2026-10-04T15:00:00Z, 2026-10-04T15:00:00Z",
            "WEEK, 2026-10-04T14:59:59Z, 2026-09-27T15:00:00Z",
            "WEEK, 2027-01-01T00:00:00Z, 2026-12-27T15:00:00Z",
            "MONTH, 2026-09-30T15:00:00Z, 2026-09-30T15:00:00Z",
            "YEAR, 2025-12-31T15:00:00Z, 2025-12-31T15:00:00Z"
    })
    void handlesMidnightMondayAndYearTransitions(
            DiscordVoiceActivityPeriod period, Instant now, Instant expected
    ) {
        assertThat(period.startAt(now)).isEqualTo(expected);
    }
}
