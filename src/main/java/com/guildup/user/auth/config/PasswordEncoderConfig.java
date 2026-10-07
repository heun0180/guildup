package com.guildup.user.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        // {bcrypt} 식별자를 포함해 향후 hash 알고리즘을 점진적으로 교체할 수 있다.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
