package com.guildup.user.verification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class EmailVerificationConfig {
    public EmailVerificationConfig(EmailVerificationProperties properties, Environment environment) {
        if ("http".equalsIgnoreCase(properties.url().getScheme())
                && !environment.acceptsProfiles(Profiles.of("local & !prod")))
            throw new IllegalArgumentException("Local HTTP email verification requires the local profile and cannot run with the prod profile");
    }
    @Bean("emailVerificationExecutor")
    public ThreadPoolTaskExecutor emailVerificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-verification-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
