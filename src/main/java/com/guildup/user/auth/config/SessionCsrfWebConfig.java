package com.guildup.user.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionCsrfWebConfig implements WebMvcConfigurer {
    private final com.guildup.user.auth.service.AuthSessionService sessions;
    public SessionCsrfWebConfig(com.guildup.user.auth.service.AuthSessionService sessions) { this.sessions = sessions; }

    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.ServletListenerRegistrationBean<com.guildup.user.auth.service.AuthSessionService> authSessionLifecycle() {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ActiveUserInterceptor(sessions)).addPathPatterns("/api/**").order(-100);
        // Existing community/developer authentication and access checks keep their precedence.
        registry.addInterceptor(new SessionCsrfInterceptor()).addPathPatterns("/api/**").order(100);
    }
}
