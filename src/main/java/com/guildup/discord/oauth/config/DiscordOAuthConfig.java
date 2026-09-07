package com.guildup.discord.oauth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Discord OAuth 기능에 필요한 설정 객체와 HTTP 클라이언트를 Spring Bean으로 등록한다. */
@Configuration
@EnableConfigurationProperties(DiscordOAuthProperties.class)
public class DiscordOAuthConfig {

    /** Discord 외부 API 호출에 공통으로 사용할 RestClient를 생성한다. */
    @Bean
    public RestClient discordOAuthRestClient() {
        return RestClient.create();
    }
}
