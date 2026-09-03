package com.guildup.discord.bot;

import com.guildup.discord.service.DiscordGuildService;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DiscordBot implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);

    private final JDA jda;
    private final DiscordGuildService discordGuildService;

    public DiscordBot(JDA jda, DiscordGuildService discordGuildService) {
        this.jda = jda;
        this.discordGuildService = discordGuildService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Discord Bot login successful - name: {}", jda.getSelfUser().getName());
        discordGuildService.logGuilds();
    }

    @PreDestroy
    public void shutdown() {
        jda.shutdown();
    }
}
