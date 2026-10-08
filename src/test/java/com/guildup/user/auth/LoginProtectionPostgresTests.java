package com.guildup.user.auth;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The production DB engine is verified only through a disposable explicit test database. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class LoginProtectionPostgresTests extends LoginProtectionFlowTests {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
    }
}
