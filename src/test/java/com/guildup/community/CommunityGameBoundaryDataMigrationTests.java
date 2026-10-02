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
        jdbc.execute("CREATE TABLE community_games (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL, game_type VARCHAR(60) NOT NULL DEFAULT 'BATTLEGROUNDS_KAKAO')");
        jdbc.execute("CREATE TABLE pubg_kill_competitions (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL, community_game_id BIGINT)");
        jdbc.execute("CREATE TABLE pubg_bingo_events (id BIGINT PRIMARY KEY, community_id BIGINT NOT NULL, community_game_id BIGINT)");
        migration = new CommunityGameBoundaryDataMigration(jdbc);
    }

    @Test
    void connectsLegacyRowsOnlyWhenCommunityHasExactlyOneGame() throws Exception {
        jdbc.update("INSERT INTO community_games (id, community_id) VALUES (11, 1), (21, 2), (22, 2)");
        jdbc.update("INSERT INTO pubg_kill_competitions (id, community_id) VALUES (101, 1), (102, 2)");
        jdbc.update("INSERT INTO pubg_bingo_events (id, community_id) VALUES (201, 1), (202, 2)");

        migration.run(new DefaultApplicationArguments());

        assertThat(gameId("pubg_kill_competitions", 101)).isEqualTo(11L);
        assertThat(gameId("pubg_bingo_events", 201)).isEqualTo(11L);
        assertThat(gameId("pubg_kill_competitions", 102)).isNull();
        assertThat(gameId("pubg_bingo_events", 202)).isNull();
    }

    @Test
    void keepsExistingGameAssignmentsOnRepeatedStartup() throws Exception {
        jdbc.update("INSERT INTO community_games (id, community_id) VALUES (11, 1)");
        jdbc.update("INSERT INTO pubg_kill_competitions VALUES (101, 1, 99)");
        jdbc.update("INSERT INTO pubg_bingo_events VALUES (201, 1, 99)");

        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(gameId("pubg_kill_competitions", 101)).isEqualTo(99L);
        assertThat(gameId("pubg_bingo_events", 201)).isEqualTo(99L);
    }

    @Test
    void unrelatedGameDoesNotCountAsPubgOrigin() throws Exception {
        jdbc.update("INSERT INTO community_games VALUES (11,1,'BATTLEGROUNDS_STEAM'),(12,1,'OTHER'),(21,2,'OTHER')");
        jdbc.update("INSERT INTO pubg_bingo_events VALUES (101,1,NULL),(102,2,NULL)");
        migration.run(new DefaultApplicationArguments());
        assertThat(gameId("pubg_bingo_events", 101)).isEqualTo(11L);
        assertThat(gameId("pubg_bingo_events", 102)).isNull();
    }

    private Long gameId(String table, long id) {
        return jdbc.queryForObject(
                "SELECT community_game_id FROM " + table + " WHERE id = ?",
                Long.class,
                id
        );
    }
}
