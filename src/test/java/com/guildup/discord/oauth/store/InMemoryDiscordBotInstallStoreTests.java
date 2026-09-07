package com.guildup.discord.oauth.store;

import com.guildup.discord.oauth.exception.InvalidDiscordBotInstallTokenException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDiscordBotInstallStoreTests {

    @Test
    void tokenCarriesSelectionAndRemainsAvailableUntilSuccessfulRemoval() {
        InMemoryDiscordBotInstallStore store = new InMemoryDiscordBotInstallStore();
        DiscordBotInstallSession expected = new DiscordBotInstallSession(1L, "100", "치즈 클랜");
        String installToken = store.createInstallToken(expected);

        assertThat(installToken).isNotBlank().isNotEqualTo("1").isNotEqualTo("100");
        assertThat(store.getInstallSession(installToken)).isEqualTo(expected);
        assertThat(store.getInstallSession(installToken)).isEqualTo(expected);

        store.removeInstallToken(installToken);

        assertThatThrownBy(() -> store.getInstallSession(installToken))
                .isInstanceOf(InvalidDiscordBotInstallTokenException.class);
    }

    @Test
    void rejectsExpiredInstallToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        InMemoryDiscordBotInstallStore store = new InMemoryDiscordBotInstallStore(clock);
        String installToken = store.createInstallToken(
                new DiscordBotInstallSession(1L, "100", "치즈 클랜")
        );

        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> store.getInstallSession(installToken))
                .isInstanceOf(InvalidDiscordBotInstallTokenException.class);
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
