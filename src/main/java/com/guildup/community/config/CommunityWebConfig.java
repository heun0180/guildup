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
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/communities", "/api/communities/**", "/api/discord/guilds/**")
                // 구버전 문의 URL도 공용 고객지원으로 접수한다. 커뮤니티 권한은 필요하지 않다.
                .excludePathPatterns("/api/communities/*/feedback");
    }
}
