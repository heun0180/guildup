package com.guildup.community.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CommunityWebConfig implements WebMvcConfigurer {
    private final CommunityAccessInterceptor interceptor;
    public CommunityWebConfig(CommunityAccessInterceptor interceptor) { this.interceptor = interceptor; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/communities", "/api/communities/**", "/api/discord/guilds/**");
    }
}
