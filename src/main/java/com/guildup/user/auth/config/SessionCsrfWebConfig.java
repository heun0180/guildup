package com.guildup.user.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionCsrfWebConfig implements WebMvcConfigurer {
    private final com.guildup.user.auth.service.AuthSessionService sessions;
    private final com.guildup.user.verification.EmailVerificationRequestInterceptor verificationRequests;
    private final com.guildup.user.verification.EmailVerificationAccessPolicy verificationPolicy;
    public SessionCsrfWebConfig(com.guildup.user.auth.service.AuthSessionService sessions,
            com.guildup.user.verification.EmailVerificationRequestInterceptor verificationRequests,
            com.guildup.user.verification.EmailVerificationAccessPolicy verificationPolicy) {
        this.sessions = sessions; this.verificationRequests = verificationRequests; this.verificationPolicy = verificationPolicy;
    }

    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.ServletListenerRegistrationBean<com.guildup.user.auth.service.AuthSessionService> authSessionLifecycle() {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ActiveUserInterceptor(sessions)).addPathPatterns("/api/**").order(-100);
        registry.addInterceptor(verificationRequests).addPathPatterns("/api/auth/**").order(101);
        registry.addInterceptor(new com.guildup.user.verification.EmailVerificationAccessInterceptor(verificationPolicy))
                .addPathPatterns("/api/communities", "/api/communities/**", "/api/community-discoveries/**", "/api/discord/guilds/**")
                .excludePathPatterns("/api/communities/*/feedback").order(102);
        // Existing community/developer authentication and access checks keep their precedence.
        registry.addInterceptor(new SessionCsrfInterceptor()).addPathPatterns("/api/**").order(100);
    }
}
