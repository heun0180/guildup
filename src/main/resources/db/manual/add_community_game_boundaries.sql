-- P0 multi-game boundary migration (PostgreSQL).
-- This migration never guesses when a community has multiple configured games.

BEGIN;

ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS community_game_id BIGINT;
ALTER TABLE pubg_bingo_events ADD COLUMN IF NOT EXISTS community_game_id BIGINT;

-- Backfill only rows whose community has exactly one configured game.
UPDATE pubg_kill_competitions target
SET community_game_id = candidate.community_game_id
FROM (
    SELECT community_id, MIN(id) AS community_game_id
    FROM community_games
    GROUP BY community_id
    HAVING COUNT(*) = 1
) candidate
WHERE target.community_id = candidate.community_id
  AND target.community_game_id IS NULL;

UPDATE pubg_bingo_events target
SET community_game_id = candidate.community_game_id
FROM (
    SELECT community_id, MIN(id) AS community_game_id
    FROM community_games
    GROUP BY community_id
    HAVING COUNT(*) = 1
) candidate
WHERE target.community_id = candidate.community_id
  AND target.community_game_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_pubg_kill_competition_community_game
    ON pubg_kill_competitions (community_game_id);
CREATE INDEX IF NOT EXISTS idx_pubg_bingo_event_community_game
    ON pubg_bingo_events (community_game_id);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pubg_kill_competition_community_game') THEN
        ALTER TABLE pubg_kill_competitions
            ADD CONSTRAINT fk_pubg_kill_competition_community_game
            FOREIGN KEY (community_game_id) REFERENCES community_games(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pubg_bingo_event_community_game') THEN
        ALTER TABLE pubg_bingo_events
            ADD CONSTRAINT fk_pubg_bingo_event_community_game
            FOREIGN KEY (community_game_id) REFERENCES community_games(id);
    END IF;
END $$;

COMMIT;

-- Audit before enforcing NOT NULL. Rows returned here belong to communities with
-- zero or multiple games and must be mapped explicitly by an operator.
SELECT 'pubg_kill_competitions' AS table_name, id, community_id
FROM pubg_kill_competitions WHERE community_game_id IS NULL
UNION ALL
SELECT 'pubg_bingo_events', id, community_id
FROM pubg_bingo_events WHERE community_game_id IS NULL
ORDER BY table_name, id;

-- Run after the audit query returns no rows:
-- ALTER TABLE pubg_kill_competitions ALTER COLUMN community_game_id SET NOT NULL;
-- ALTER TABLE pubg_bingo_events ALTER COLUMN community_game_id SET NOT NULL;
