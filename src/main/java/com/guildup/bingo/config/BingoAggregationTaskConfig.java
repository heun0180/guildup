package com.guildup.bingo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class BingoAggregationTaskConfig {
    @Bean(name = "bingoAggregationExecutor")
    public ThreadPoolTaskExecutor bingoAggregationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("bingo-aggregation-");
        executor.initialize();
        return executor;
    }
}
