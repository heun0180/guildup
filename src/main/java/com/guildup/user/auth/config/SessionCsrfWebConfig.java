package com.guildup.user.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionCsrfWebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Existing community/developer authentication and access checks keep their precedence.
        registry.addInterceptor(new SessionCsrfInterceptor()).addPathPatterns("/api/**").order(100);
    }
}
