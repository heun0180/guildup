package com.guildup.monitoring;

import com.guildup.monitoring.logging.SafeLogText;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SafeLogTextBoundedTests {
    @Test
    void limitedOnlyRetainsABoundedPrefixEvenWithALargerRequestedLimit() {
        String result = SafeLogText.limited("x".repeat(2_000_000) + "tail-marker", 20_000);

        assertThat(result).hasSizeLessThanOrEqualTo(8192 + "…[truncated]".length())
                .endsWith("…[truncated]").doesNotContain("tail-marker");
    }

    @Test
    void repeatedSensitiveWordsWithoutAnAssignmentDoNotCauseRegexBacktracking() {
        String result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> SafeLogText.limited("password".repeat(1000) + "?", 2000));
        assertThat(result).hasSizeLessThanOrEqualTo(2000 + "…[truncated]".length());
    }

    @Test
    void cutQuotedSensitiveValuesAreFullyMaskedIncludingSpaces() {
        for (String prefix : List.of("password=\"", "clientSecret='", "\"access_token\":\"")) {
            String result = SafeLogText.limited(prefix + "private value ".repeat(2000) + "\"", 20_000);

            assertThat(result).contains("[REDACTED]").doesNotContain("private", "value");
        }
    }

    @Test
    void escapedQuotesAndTerminalBackslashesCannotEndSensitiveValuesEarly() {
        String result = SafeLogText.redact("password=\"private \\\"quoted private\\\" remainder\" requestId=req-1");
        assertThat(result).doesNotContain("private", "remainder").contains("[REDACTED]", "requestId=req-1");

        String cut = "password=\"" + "private ".repeat(1100);
        String boundary = cut.substring(0, 8191) + "\\" + "private remainder\"";
        assertThat(SafeLogText.limited(boundary, 20_000)).contains("[REDACTED]")
                .doesNotContain("private", "remainder");
    }

    @Test
    void truncatedHeadersBearerAndJwtDoNotExposeTheirRemainingPrefix() {
        for (String prefix : List.of("Authorization: Bearer ", "Cookie: JSESSIONID=", "Bearer ", "eyJ")) {
            String result = SafeLogText.limited(prefix + "AbCdEfGh".repeat(2000), 20_000);
            assertThat(result).contains("[REDACTED]").doesNotContain("AbCdEfGh");
        }
    }

    @Test
    void sqlAndHttpBodiesRemainMaskedWhenClosingDelimiterIsOutsidePrefix() {
        for (String prefix : List.of("JDBC exception executing SQL [insert into users values (",
                "[ select * from members where name='", "500 response body: \"")) {
            String result = SafeLogText.limited(prefix + "private value ".repeat(2000) + "\"]", 20_000);
            assertThat(result).contains("REDACTED").doesNotContain("private value");
        }
    }

    @Test
    void fileRedactionStillPreservesExceptionStackBeyondLiveLogPrefix() {
        String full = "stack frame\n".repeat(1000)
                + "Caused by: password=\"private value\"\n"
                + "\tat com.guildup.Job.run(Job.java:42) terminal-frame";

        String result = SafeLogText.redact(full);
        assertThat(result).hasSizeGreaterThan(8192).contains("terminal-frame", "[REDACTED]")
                .doesNotContain("private value", "…[truncated]");
    }

    @Test
    void retainsSafeContextAndDoesNotDuplicateRedactionBrackets() {
        String result = SafeLogText.redact("communityId=12 accessToken=[REDACTED] requestId=req-1");
        assertThat(result).isEqualTo("communityId=12 accessToken=[REDACTED] requestId=req-1");
        assertThat(SafeLogText.endpoint("/api/oauth/results/private-token?access_token=private"))
                .isEqualTo("/api/oauth/results/[REDACTED]");
    }
}
