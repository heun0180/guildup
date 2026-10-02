package com.guildup.monitoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class MonitoringExecutorConfig {
    @Bean(name = "monitoringEventExecutor", destroyMethod = "stopWriter")
    public MonitoringEventExecutor monitoringEventExecutor() { return new MonitoringEventExecutor(); }
}
