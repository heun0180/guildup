package com.guildup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** GuildUp 백엔드의 Spring Boot 시작점이다. */
@SpringBootApplication
public class GuildupBackendApplication {

    /** Spring 컨테이너와 내장 웹 서버를 실행한다. */
    public static void main(String[] args) {
        SpringApplication.run(GuildupBackendApplication.class, args);
    }

}
