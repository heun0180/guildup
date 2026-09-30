package com.guildup.developer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DeveloperWebConfig implements WebMvcConfigurer {
    private final DeveloperAccessInterceptor interceptor;

    public DeveloperWebConfig(DeveloperAccessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/developer", "/developer/**", "/api/developer/**");
    }
}
