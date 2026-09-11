package com.guildup.discord.config;

import com.guildup.discord.bot.DiscordVoiceEventListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Discord 봇 토큰으로 JDA 클라이언트를 구성한다. */
@Configuration
public class DiscordConfig {

    /**
     * 전체 멤버 적재 없이 온디맨드 조회와 현재 음성 접속자 캐시만 활성화한다.
     */
    @Bean(destroyMethod = "")
    public JDA jda(
            @Value("${DISCORD_BOT_TOKEN}") String token,
            DiscordVoiceEventListener voiceEventListener
    ) throws InterruptedException {
        return JDABuilder.createLight(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setChunkingFilter(ChunkingFilter.NONE)
                .addEventListeners(voiceEventListener)
                .build()
                .awaitReady();
    }
}
