package com.guildup.community;

import com.guildup.community.config.CommunityGameBoundaryDataMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityGameBoundaryDataMigrationTests {

    private JdbcTemplate jdbc;
    private CommunityGameBoundaryDataMigration migration;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:game-boundary;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE community_games (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE kill_competitions (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL, community_game_id BIGINT)");
        jdbc.execute("CREATE TABLE bingo_events (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL, community_game_id BIGINT)");
        migration = new CommunityGameBoundaryDataMigration(jdbc);
    }

    @Test
    void connectsLegacyRowsOnlyWhenCommunityHasExactlyOneGame() throws Exception {
        jdbc.update("INSERT INTO community_games (id, community_id) VALUES (11, 1), (21, 2), (22, 2)");
        jdbc.update("INSERT INTO kill_competitions (id, community_id) VALUES (101, 1), (102, 2)");
        jdbc.update("INSERT INTO bingo_events (id, community_id) VALUES (201, 1), (202, 2)");

        migration.run(new DefaultApplicationArguments());

        assertThat(gameId("kill_competitions", 101)).isEqualTo(11L);
        assertThat(gameId("bingo_events", 201)).isEqualTo(11L);
        assertThat(gameId("kill_competitions", 102)).isNull();
        assertThat(gameId("bingo_events", 202)).isNull();
    }

    @Test
    void keepsExistingGameAssignmentsOnRepeatedStartup() throws Exception {
        jdbc.update("INSERT INTO community_games (id, community_id) VALUES (11, 1)");
        jdbc.update("INSERT INTO kill_competitions VALUES (101, 1, 99)");
        jdbc.update("INSERT INTO bingo_events VALUES (201, 1, 99)");

        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(gameId("kill_competitions", 101)).isEqualTo(99L);
        assertThat(gameId("bingo_events", 201)).isEqualTo(99L);
    }

    private Long gameId(String table, long id) {
        return jdbc.queryForObject(
                "SELECT community_game_id FROM " + table + " WHERE id = ?",
                Long.class,
                id
        );
    }
}
