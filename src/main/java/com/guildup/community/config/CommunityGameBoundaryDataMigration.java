package com.guildup.community.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 기존 킬내기와 빙고 데이터를 해당 커뮤니티의 유일한 게임에 안전하게 연결한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommunityGameBoundaryDataMigration implements ApplicationRunner {

    private static final String BACKFILL_KILL_COMPETITIONS = """
            UPDATE kill_competitions target
            SET community_game_id = (
                SELECT MIN(game.id)
                FROM community_games game
                WHERE game.community_id = target.community_id
            )
            WHERE target.community_game_id IS NULL
              AND 1 = (
                  SELECT COUNT(*)
                  FROM community_games game
                  WHERE game.community_id = target.community_id
              )
            """;

    private static final String BACKFILL_BINGO_EVENTS = """
            UPDATE bingo_events target
            SET community_game_id = (
                SELECT MIN(game.id)
                FROM community_games game
                WHERE game.community_id = target.community_id
            )
            WHERE target.community_game_id IS NULL
              AND 1 = (
                  SELECT COUNT(*)
                  FROM community_games game
                  WHERE game.community_id = target.community_id
              )
            """;

    private final JdbcTemplate jdbcTemplate;

    public CommunityGameBoundaryDataMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbcTemplate.update(BACKFILL_KILL_COMPETITIONS);
        jdbcTemplate.update(BACKFILL_BINGO_EVENTS);
    }
}
