package com.guildup.discord.bot;

import com.guildup.community.service.CommunityMemberRealtimeSyncService;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.beans.factory.annotation.Autowired;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 캐시 여부와 관계없이 Discord 멤버의 현재 역할을 사용자 단위 동기화 서비스에 전달한다. */
@Component
public class DiscordMemberEventListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberEventListener.class);

    private final CommunityMemberRealtimeSyncService realtimeSyncService;
    private final DiscordMemberEventDispatcher dispatcher;
    private MonitoringEventService monitoring;

    public DiscordMemberEventListener(
            CommunityMemberRealtimeSyncService realtimeSyncService,
            DiscordMemberEventDispatcher dispatcher
    ) {
        this.realtimeSyncService = realtimeSyncService;
        this.dispatcher = dispatcher;
    }

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    /** JDA 6에서 캐시되지 않은 멤버 업데이트도 제공하는 기본 경로다. */
    @Override
    public void onGuildMemberUpdate(@NotNull GuildMemberUpdateEvent event) {
        synchronize(event.getMember(), "member update");
    }

    @Override
    public void onGuildMemberRoleAdd(@NotNull GuildMemberRoleAddEvent event) {
        synchronize(event.getMember(), "role add");
    }

    @Override
    public void onGuildMemberRoleRemove(@NotNull GuildMemberRoleRemoveEvent event) {
        synchronize(event.getMember(), "role remove");
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        synchronize(event.getMember(), "guild join");
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();
        dispatcher.dispatch(guildId, userId, () -> {
            try {
                realtimeSyncService.memberRemoved(guildId, userId, event.getUser().isBot());
            } catch (RuntimeException exception) {
                log.error("Discord guild member remove sync failed - guildId: {}, discordUserId: {}",
                        guildId, userId, exception);
                recordMemberFailure("member remove", guildId, userId, exception);
            }
        });
    }

    private void synchronize(net.dv8tion.jda.api.entities.Member member, String eventType) {
        String guildId = member.getGuild().getId();
        String userId = member.getUser().getId();
        dispatcher.dispatch(guildId, userId, () -> {
            try {
                realtimeSyncService.synchronize(member);
            } catch (RuntimeException exception) {
                log.error("Discord member realtime sync failed - event: {}, guildId: {}, discordUserId: {}",
                        eventType, guildId, userId, exception);
                recordMemberFailure(eventType, guildId, userId, exception);
            }
        });
    }

    private void recordMemberFailure(String eventType, String guildId, String discordUserId,
                                     RuntimeException exception) {
        if (monitoring == null) return;
        monitoring.recordError(MonitoringCategory.DISCORD, MonitoringEventCode.DISCORD_MEMBER_LOOKUP_FAILED,
                "Discord member synchronization failed", null, null, "discordGuildId=" + guildId,
                Map.of("eventType", eventType, "discordGuildId", guildId,
                        "discordUserId", discordUserId,
                        "exceptionClass", exception.getClass().getSimpleName()));
    }
}
