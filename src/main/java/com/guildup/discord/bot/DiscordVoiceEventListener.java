package com.guildup.discord.bot;

import com.guildup.discord.service.DiscordVoiceSessionService;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** JDA 음성 상태 이벤트를 사용자별로 직렬화해 세션 서비스에 전달한다. */
@Component
public class DiscordVoiceEventListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordVoiceEventListener.class);

    private final DiscordVoiceSessionService sessionService;
    private final Clock clock;
    private final Object[] eventLocks = new Object[64];
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    public DiscordVoiceEventListener(DiscordVoiceSessionService sessionService, Clock clock) {
        this.sessionService = sessionService;
        this.clock = clock;
        for (int index = 0; index < eventLocks.length; index++) {
            eventLocks[index] = new Object();
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getMember().getUser().isBot()) return;

        String guildId = event.getGuild().getId();
        String userId = event.getMember().getId();
        String key = guildId + ':' + userId;
        Object lock = eventLocks[Math.floorMod(key.hashCode(), eventLocks.length)];
        try (var ignored = LogContext.scope(java.util.Map.of("discordGuildId", guildId,
                "discordUserId", userId, "jobName", "discordVoiceUpdate"))) {
            synchronized (lock) {
                var joined = event.getChannelJoined();
                sessionService.handleVoiceUpdate(
                        guildId,
                        userId,
                        joined == null ? null : joined.getId(),
                        joined == null ? null : joined.getName(),
                        clock.instant()
                );
            }
        } catch (RuntimeException exception) {
            log.error("Discord voice event failed. jobName=discordVoiceUpdate, discordGuildId={}, discordUserId={}, discordChannelId={}",
                    guildId, userId, event.getChannelJoined() == null ? null : event.getChannelJoined().getId(), exception);
            if (monitoring != null) monitoring.recordError(MonitoringCategory.DISCORD,
                    MonitoringEventCode.DISCORD_API_FAILED, "Discord voice event processing failed",
                    null, null, "discordGuildId=" + guildId, java.util.Map.of(
                            "jobName", "discordVoiceUpdate", "discordGuildId", guildId,
                            "discordUserId", userId, "exceptionClass", exception.getClass().getSimpleName()));
        }
    }
}
