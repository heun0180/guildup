package com.guildup.discord.bot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.guildup.discord.service.DiscordVoiceSessionService;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordVoiceEventLoggingTests {
    @Test
    void failureAtJdaBoundaryHasTraceContextAndMonitoringEvent() {
        var sessions = mock(DiscordVoiceSessionService.class);
        var monitoring = mock(MonitoringEventService.class);
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        var listener = new DiscordVoiceEventListener(sessions, Clock.fixed(now, ZoneOffset.UTC));
        listener.configureMonitoring(monitoring);
        var event = mock(GuildVoiceUpdateEvent.class, RETURNS_DEEP_STUBS);
        when(event.getGuild().getId()).thenReturn("guild-1");
        when(event.getMember().getId()).thenReturn("user-2");
        when(event.getChannelJoined()).thenReturn(null);
        doThrow(new IllegalStateException("database unavailable")).when(sessions)
                .handleVoiceUpdate("guild-1", "user-2", null, null, now);

        Logger logger = (Logger) LoggerFactory.getLogger(DiscordVoiceEventListener.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> listener.onGuildVoiceUpdate(event)).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("guild-1", "user-2", "discordVoiceUpdate");
            verify(monitoring).recordError(eq(MonitoringCategory.DISCORD), eq(MonitoringEventCode.DISCORD_API_FAILED),
                    anyString(), isNull(), isNull(), eq("discordGuildId=guild-1"), anyMap());
            assertThat(org.slf4j.MDC.get("discordUserId")).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
