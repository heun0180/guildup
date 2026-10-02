package com.guildup.discord.service;

import com.guildup.discord.exception.DiscordResourceNotFoundException;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.beans.factory.annotation.Autowired;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JDA 캐시를 통해 봇이 참여한 Discord 서버를 조회한다. */
@Service
public class DiscordGuildService {
    private static final Logger log = LoggerFactory.getLogger(DiscordGuildService.class);

    private final JDA jda;
    private MonitoringEventService monitoring;

    public DiscordGuildService(JDA jda) {
        this.jda = jda;
    }

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    /** 봇이 현재 참여 중인 모든 Discord 서버를 반환한다. */
    public List<Guild> getGuilds() {
        return jda.getGuilds();
    }

    /** 서버 ID로 Discord 서버를 찾고 없으면 404 예외를 발생시킨다. */
    public Guild getGuildById(String guildId) {
        return findGuildById(guildId).orElseThrow(() -> {
            log.warn("Discord guild unavailable in JDA cache. discordGuildId={}", guildId);
            if (monitoring != null) monitoring.recordWarn(MonitoringCategory.DISCORD,
                    MonitoringEventCode.DISCORD_GUILD_CONNECTION_FAILED,
                    "Discord guild is not available to JDA", null, null,
                    "discordGuildId=" + guildId, Map.of("discordGuildId", guildId));
            return new DiscordResourceNotFoundException("Discord guild not found: " + guildId);
        });
    }

    /** 잘못된 형식의 ID까지 포함해 서버 조회 실패를 Optional.empty로 통일한다. */
    public Optional<Guild> findGuildById(String guildId) {
        try {
            return Optional.ofNullable(jda.getGuildById(guildId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
