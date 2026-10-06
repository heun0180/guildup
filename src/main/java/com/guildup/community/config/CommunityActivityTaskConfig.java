package com.guildup.community.config;

import com.guildup.monitoring.logging.LogContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 활동 조회는 웹 요청과 다른 기능의 실행기를 점유하지 않는다. */
@Configuration
public class CommunityActivityTaskConfig {
    @Bean(name = "communityActivitySyncExecutor")
    @DependsOn({"entityManagerFactory", "pubgMatchService"})
    public ThreadPoolTaskExecutor communityActivitySyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        // 긴 대기열과 CallerRunsPolicy를 피한다. 포화 시 접수를 거절하고 FAILED로 복구한다.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("community-activity-sync-");
        executor.setTaskDecorator(LogContext::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);
        return executor;
    }
}
