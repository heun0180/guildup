package com.guildup.mail.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApplicationMailProperties.class)
@org.springframework.context.annotation.Import(com.guildup.mail.ApplicationMailService.class)
public class ApplicationMailConfig {
}
