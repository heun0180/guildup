package com.guildup.discord.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Discord 복구 작업이 웹 요청과 JDA 이벤트 스레드를 점유하지 않도록 실행기를 분리한다. */
@Configuration
public class DiscordTaskExecutorConfig {

    @Bean(name = "discordMemberEventExecutor")
    public ThreadPoolTaskExecutor discordMemberEventExecutor(
            @Value("${discord.member-events.concurrency:8}") int concurrency,
            @Value("${discord.member-events.queue-capacity:10000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("discord-member-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "discordMemberReconciliationExecutor")
    public ThreadPoolTaskExecutor discordMemberReconciliationExecutor(
            @Value("${discord.member-reconciliation.concurrency:5}") int concurrency,
            @Value("${discord.member-reconciliation.queue-capacity:10000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("discord-member-reconcile-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "discordRecoveryBootstrapExecutor")
    public ThreadPoolTaskExecutor discordRecoveryBootstrapExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("discord-recovery-bootstrap-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
