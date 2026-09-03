package com.guildup.discord.service;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiscordGuildService {

    private static final Logger log = LoggerFactory.getLogger(DiscordGuildService.class);

    private final JDA jda;
    private final DiscordMemberService discordMemberService;

    public DiscordGuildService(JDA jda, DiscordMemberService discordMemberService) {
        this.jda = jda;
        this.discordMemberService = discordMemberService;
    }

    public List<Guild> getGuilds() {
        return jda.getGuilds();
    }

    public Guild getGuildById(String guildId) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Discord guild not found: " + guildId);
        }

        return guild;
    }

    public void logGuilds() {
        for (Guild guild : getGuilds()) {
            log.info("Discord guild - name: {}, id: {}", guild.getName(), guild.getId());
            discordMemberService.logMembers(guild);
        }
    }
}
