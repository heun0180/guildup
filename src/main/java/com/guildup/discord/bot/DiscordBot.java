package com.guildup.discord.bot;

import com.guildup.discord.service.DiscordGuildService;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 애플리케이션 시작/종료 생명주기에 Discord JDA 클라이언트를 연결한다. */
@Component
public class DiscordBot implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);

    private final JDA jda;
    private final DiscordGuildService discordGuildService;

    public DiscordBot(JDA jda, DiscordGuildService discordGuildService) {
        this.jda = jda;
        this.discordGuildService = discordGuildService;
    }

    /** Spring Boot 준비가 끝나면 봇 로그인과 접근 가능한 서버/멤버를 로그로 확인한다. */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Discord Bot login successful - name: {}", jda.getSelfUser().getName());
        discordGuildService.logGuilds();
    }

    /** 애플리케이션 종료 전에 Discord Gateway 연결을 정상적으로 닫는다. */
    @PreDestroy
    public void shutdown() {
        jda.shutdown();
    }
}
