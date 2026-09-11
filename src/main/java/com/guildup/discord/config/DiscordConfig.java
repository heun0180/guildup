package com.guildup.discord.config;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Discord 봇 토큰으로 JDA 클라이언트를 구성한다. */
@Configuration
public class DiscordConfig {

    /**
     * 서버별 온디맨드 멤버 조회에 필요한 GUILD_MEMBERS 인텐트만 활성화한다.
     * 시작 시 전체 서버 멤버를 적재하거나 메모리에 계속 보관하지 않는다.
     */
    @Bean(destroyMethod = "")
    public JDA jda(@Value("${DISCORD_BOT_TOKEN}") String token) throws InterruptedException {
        return JDABuilder.createLight(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .setChunkingFilter(ChunkingFilter.NONE)
                .build()
                .awaitReady();
    }
}
