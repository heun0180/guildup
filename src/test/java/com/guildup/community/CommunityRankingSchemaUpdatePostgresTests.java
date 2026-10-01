package com.guildup.community;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.RankingPeriodType;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 운영 데이터가 있는 기존 테이블에 ddl-auto=update가 기본값을 포함해 컬럼을 추가하는지 검증한다. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class CommunityRankingSchemaUpdatePostgresTests {
    @Test
    void schemaUpdateAddsDefaultBeforeLoadingExistingCommunity() throws Exception {
        String url = System.getenv("TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
        String schema = "ranking_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            try {
                statement.execute("create table " + schema + ".communities (id bigserial primary key, name varchar(255) not null, created_at timestamptz not null)");
                statement.execute("insert into " + schema + ".communities(name, created_at) values ('기존 커뮤니티', current_timestamp)");
                var configuration = new Configuration()
                        .addAnnotatedClass(Community.class)
                        .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                        .setProperty("hibernate.connection.url", url)
                        .setProperty("hibernate.connection.username", user)
                        .setProperty("hibernate.connection.password", password)
                        .setProperty("hibernate.default_schema", schema)
                        .setProperty("hibernate.hbm2ddl.auto", "update")
                        .setProperty("hibernate.show_sql", "false");
                try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
                    var existing = session.createQuery("from Community", Community.class).getResultList();
                    assertThat(existing).singleElement().satisfies(community -> {
                        assertThat(community.getName()).isEqualTo("기존 커뮤니티");
                        assertThat(community.getRankingPeriodType()).isEqualTo(RankingPeriodType.ALL_TIME);
                    });
                    session.beginTransaction();
                    session.persist(new Community("새 커뮤니티"));
                    session.getTransaction().commit();
                }
                try (var result = statement.executeQuery("select column_default, is_nullable from information_schema.columns where table_schema = '" + schema + "' and table_name = 'communities' and column_name = 'ranking_period_type'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("column_default")).contains("ALL_TIME");
                    assertThat(result.getString("is_nullable")).isEqualTo("NO");
                }
                try (var result = statement.executeQuery("select count(*) from " + schema + ".communities where ranking_period_type = 'ALL_TIME'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(2);
                }
            } finally {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
