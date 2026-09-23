package com.guildup.pubg.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PubgApiRequestGovernorTests {

    @Test
    void spacesRequestsFromDifferentFeaturesThroughOneGovernor() {
        List<Long> waits = new ArrayList<>();
        PubgApiRequestGovernor governor = new PubgApiRequestGovernor(
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
                waits::add,
                Duration.ofMillis(250)
        );

        governor.acquire();
        governor.acquire();
        governor.acquire();

        assertThat(waits).containsExactly(250L, 500L);
    }

    @Test
    void rateLimitHeadersSlowSubsequentRequestsToFitRemainingWindow() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        List<Long> waits = new ArrayList<>();
        PubgApiRequestGovernor governor = new PubgApiRequestGovernor(
                Clock.fixed(now, ZoneOffset.UTC), waits::add, Duration.ofMillis(100)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "2");
        headers.set("X-RateLimit-Reset", String.valueOf(now.plusSeconds(10).getEpochSecond()));

        governor.observe(headers);
        governor.acquire();
        governor.acquire();

        assertThat(waits).containsExactly(5_000L);
    }
}
