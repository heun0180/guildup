package com.guildup.user.reset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetConfig {
    public PasswordResetConfig(PasswordResetProperties properties, Environment environment) {
        if ("http".equalsIgnoreCase(properties.url().getScheme())
                && !environment.acceptsProfiles(Profiles.of("local & !prod")))
            throw new IllegalArgumentException("Local HTTP password reset requires local without prod profile");
    }
    @Bean("passwordResetRequestExecutor")
    public ThreadPoolTaskExecutor requests() { return executor("password-reset-request-", 2, 100); }
    @Bean("passwordResetMailExecutor")
    public ThreadPoolTaskExecutor mail() { return executor("password-reset-mail-", 2, 30); }
    private ThreadPoolTaskExecutor executor(String prefix, int threads, int queue) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads); executor.setMaxPoolSize(threads); executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix); executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15); return executor;
    }
    @Bean
    public org.springframework.boot.ApplicationRunner passwordResetQuotaInitialization(PasswordResetService service, PasswordResetEvents events) {
        return args -> {
            try { service.initializeQuota(); }
            catch (org.springframework.dao.DataIntegrityViolationException concurrentInitializer) { /* Other instance inserted singleton. */ }
            catch (RuntimeException unavailable) { events.storageFailed(); /* Mail requests fail closed until restored. */ }
        };
    }
}
