package com.guildup.pubg;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Runs the deployment SQL against disposable schemas; never connects to the default application DB. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class PubgPlatformMigrationPostgresTests {
    private Connection db;
    private String schema;

    @BeforeEach void createLegacySchema() throws Exception {
        db = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        schema = "platform_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema + "; SET search_path TO " + schema);
        execute("""
                CREATE TABLE community_games (id bigint PRIMARY KEY, community_id bigint NOT NULL, game_type varchar(60) NOT NULL);
                CREATE TABLE community_member_accounts (
                  id bigint PRIMARY KEY, community_id bigint NOT NULL, community_member_id bigint NOT NULL,
                  provider varchar(30) NOT NULL, external_user_id varchar(100) NOT NULL, nickname varchar(100),
                  CONSTRAINT randomly_named_member_unique UNIQUE (community_member_id, provider));
                CREATE UNIQUE INDEX randomly_named_user_unique ON community_member_accounts (community_id, provider, external_user_id);
                CREATE TABLE pubg_bingo_events (id bigint PRIMARY KEY, community_id bigint NOT NULL, community_game_id bigint);
                CREATE TABLE pubg_kill_competitions (id bigint PRIMARY KEY, community_id bigint NOT NULL, community_game_id bigint);
                CREATE TABLE pubg_kill_competition_participants (id bigint PRIMARY KEY, competition_id bigint, community_member_id bigint, pubg_account_id varchar(100));
                """);
        execute(Files.readString(Path.of("src/test/resources/db/legacy_pubg_match_facts.sql")));
    }

    @AfterEach void removeOwnSchema() throws Exception {
        if (db != null) {
            execute("ROLLBACK; SET search_path TO public; DROP SCHEMA " + schema + " CASCADE");
            db.close();
        }
    }

    @Test void backfillsOnlyAnUnambiguousPubgGameAndKeepsAllOriginalRows() throws Exception {
        execute("""
                INSERT INTO community_games VALUES (10,1,'BATTLEGROUNDS_KAKAO'),(11,1,'OTHER_GAME'),(20,2,'BATTLEGROUNDS_STEAM');
                INSERT INTO community_member_accounts VALUES (1,1,1,'PUBG','account-k','K'),(2,2,2,'PUBG','account-s','S'),(3,1,1,'DISCORD','discord','D');
                INSERT INTO pubg_bingo_events VALUES (1,1,NULL),(2,2,NULL);
                INSERT INTO pubg_kill_competitions VALUES (1,1,NULL),(2,2,NULL);
                """);
        phaseOne(); migration("audit_pubg_platform_boundaries.sql"); phaseTwo(); phaseOne(); phaseTwo(); // Safe to re-run.
        assertThat(value("SELECT platform FROM community_member_accounts WHERE id=1")).isEqualTo("KAKAO");
        assertThat(value("SELECT platform FROM community_member_accounts WHERE id=2")).isEqualTo("STEAM");
        assertThat(value("SELECT platform FROM community_member_accounts WHERE id=3")).isNull();
        assertThat(value("SELECT count(*) FROM community_member_accounts")).isEqualTo("3");
        assertThat(value("SELECT community_game_id FROM pubg_bingo_events WHERE id=1")).isEqualTo("10");
        assertThat(value("SELECT community_game_id FROM pubg_kill_competitions WHERE id=2")).isEqualTo("20");
    }

    @Test void ambiguousAccountStopsEnforcementBeforeLegacyConstraintsAreDropped() throws Exception {
        execute("""
                INSERT INTO community_games VALUES (10,1,'BATTLEGROUNDS_KAKAO'),(11,1,'BATTLEGROUNDS_STEAM');
                INSERT INTO community_member_accounts VALUES (1,1,1,'PUBG','original','Original');
                """);
        phaseOne();
        assertThat(value("SELECT platform FROM community_member_accounts WHERE id=1")).isNull();
        assertThatThrownBy(this::phaseTwo).isInstanceOf(SQLException.class).hasMessageContaining("Unresolved/invalid account platform");
        execute("ROLLBACK");
        assertThat(value("SELECT external_user_id FROM community_member_accounts WHERE id=1")).isEqualTo("original");
        assertThat(value("SELECT count(*) FROM pg_constraint WHERE conrelid='community_member_accounts'::regclass AND conname='randomly_named_member_unique'"))
                .isEqualTo("1");
        assertThat(value("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='randomly_named_user_unique'"))
                .isEqualTo("1");
    }

    @Test void eventWithAmbiguousOriginStopsEnforcementAndCannotBeAssignedToAnotherCommunity() throws Exception {
        execute("""
                INSERT INTO community_games VALUES (10,1,'BATTLEGROUNDS_KAKAO'),(11,1,'BATTLEGROUNDS_STEAM'),(20,2,'BATTLEGROUNDS_STEAM');
                INSERT INTO pubg_bingo_events VALUES (1,1,NULL);
                """);
        phaseOne();
        assertThatThrownBy(this::phaseTwo).isInstanceOf(SQLException.class).hasMessageContaining("Unresolved/invalid event");
        execute("ROLLBACK; UPDATE pubg_bingo_events SET community_game_id=10 WHERE id=1");
        phaseTwo();
        assertThatThrownBy(() -> execute("UPDATE pubg_bingo_events SET community_game_id=20 WHERE id=1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_pubg_bingo_event_game_owner");
        assertThatThrownBy(() -> execute("INSERT INTO pubg_kill_competitions VALUES (2,1,NULL)"))
                .isInstanceOf(SQLException.class);
    }

    @Test void constraintsAllowTwoPubgPlatformsAndPreserveDiscordNullUniqueness() throws Exception {
        phaseOne(); phaseTwo();
        execute("""
                INSERT INTO community_member_accounts VALUES (1,1,1,'PUBG','same-account','K','KAKAO'),(2,1,1,'PUBG','same-account','S','STEAM'),(3,1,1,'DISCORD','discord','D',NULL);
                """);
        rejected("INSERT INTO community_member_accounts VALUES (4,1,1,'PUBG','another','duplicate','KAKAO')");
        rejected("INSERT INTO community_member_accounts VALUES (4,1,2,'PUBG','same-account','duplicate','STEAM')");
        rejected("INSERT INTO community_member_accounts VALUES (4,1,1,'DISCORD','another','duplicate',NULL)");
        rejected("INSERT INTO community_member_accounts VALUES (4,1,2,'DISCORD','discord','duplicate',NULL)");
        rejected("INSERT INTO community_member_accounts VALUES (4,1,2,'PUBG','missing','invalid',NULL)");
        rejected("INSERT INTO community_member_accounts VALUES (4,1,2,'DISCORD','invalid','invalid','STEAM')");
        execute("""
                INSERT INTO pubg_matches (match_id,shard,started_at,created_at,updated_at) VALUES
                  ('same-match','kakao',now(),now(),now()),('same-match','steam',now(),now(),now());
                """);
        rejected("INSERT INTO pubg_matches (match_id,shard,started_at,created_at,updated_at) VALUES ('same-match','steam',now(),now(),now())");
        assertThat(value("SELECT count(*) FROM community_member_accounts")).isEqualTo("3");
        assertThat(value("SELECT count(*) FROM pubg_matches")).isEqualTo("2");
    }

    @Test void newFactDdlAndMigratedLegacyFactSchemaAgreeIncludingUnknownHistoricalStats() throws Exception {
        execute("""
                INSERT INTO pubg_matches (id,match_id,shard,started_at,created_at,updated_at) VALUES (1,'legacy','kakao',now(),now(),now());
                INSERT INTO pubg_match_players (pubg_match_id,pubg_account_id,team_number,kills,assists,dbnos,headshot_kills,revives,heals,boosts,placement,win,created_at,updated_at)
                  VALUES (1,'legacy-account',1,2,0,0,0,0,0,0,10,false,now(),now());
                """);
        phaseOne(); phaseTwo();
        assertThat(value("SELECT time_survived FROM pubg_match_players")).isNull();
        assertThat(value("SELECT road_kills FROM pubg_match_players")).isNull();
        var migrated = factShape();
        execute("DROP TABLE pubg_match_kills, pubg_match_players, pubg_matches");
        execute(Files.readString(Path.of("src/main/resources/db/manual/add_pubg_match_facts.sql")));
        assertThat(factShape()).isEqualTo(migrated);
    }

    private List<String> factShape() throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery("""
                SELECT table_name||':'||column_name||':'||data_type||':'||is_nullable FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name IN ('pubg_matches','pubg_match_players','pubg_match_kills')
                UNION ALL
                SELECT c.relname||':'||pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
                WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('pubg_matches','pubg_match_players','pubg_match_kills')
                ORDER BY 1
                """)) { while (rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private void phaseOne() throws Exception { migration("add_pubg_platform_boundaries.sql"); }
    private void phaseTwo() throws Exception { migration("enforce_pubg_platform_boundaries.sql"); }
    private void migration(String file) throws Exception { execute(Files.readString(Path.of("src/main/resources/db/manual", file))); }
    private void execute(String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    private String value(String sql) throws SQLException { try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); } }
    private void rejected(String sql) { assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class); }
}
