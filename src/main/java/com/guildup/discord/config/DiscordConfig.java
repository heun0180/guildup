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
     * 서버 멤버 조회에 필요한 GUILD_MEMBERS 인텐트와 전체 멤버 캐시를 활성화한다.
     * awaitReady로 Discord 연결이 준비된 뒤에만 Bean 생성을 완료한다.
     */
    @Bean(destroyMethod = "")
    public JDA jda(@Value("${DISCORD_BOT_TOKEN}") String token) throws InterruptedException {
        return JDABuilder.createLight(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .build()
                .awaitReady();
    }
}
