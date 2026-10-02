-- Phase 1: additive expansion. Run with psql -v ON_ERROR_STOP=1 -f ... .
-- Stop application writers before the final enforcement phase. No account/event is deleted.
BEGIN;
ALTER TABLE community_member_accounts ADD COLUMN IF NOT EXISTS platform varchar(20);
ALTER TABLE pubg_match_players ADD COLUMN IF NOT EXISTS time_survived double precision;
ALTER TABLE pubg_match_players ADD COLUMN IF NOT EXISTS road_kills integer;
ALTER TABLE pubg_bingo_events ADD COLUMN IF NOT EXISTS community_game_id bigint;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS community_game_id bigint;

-- Only one known PUBG CommunityGame is evidence of origin. Other games do not count.
WITH unambiguous AS (
    SELECT community_id, min(id) AS game_id,
           CASE min(game_type) WHEN 'BATTLEGROUNDS_KAKAO' THEN 'KAKAO'
                               WHEN 'BATTLEGROUNDS_STEAM' THEN 'STEAM' END AS platform
    FROM community_games
    WHERE game_type IN ('BATTLEGROUNDS_KAKAO', 'BATTLEGROUNDS_STEAM')
    GROUP BY community_id HAVING count(*) = 1
)
UPDATE community_member_accounts a SET platform = u.platform
FROM unambiguous u
WHERE a.community_id = u.community_id AND a.provider = 'PUBG' AND a.platform IS NULL;

WITH unambiguous AS (
    SELECT community_id, min(id) AS game_id FROM community_games
    WHERE game_type IN ('BATTLEGROUNDS_KAKAO', 'BATTLEGROUNDS_STEAM')
    GROUP BY community_id HAVING count(*) = 1
)
UPDATE pubg_bingo_events e SET community_game_id = u.game_id FROM unambiguous u
WHERE e.community_id = u.community_id AND e.community_game_id IS NULL;
WITH unambiguous AS (
    SELECT community_id, min(id) AS game_id FROM community_games
    WHERE game_type IN ('BATTLEGROUNDS_KAKAO', 'BATTLEGROUNDS_STEAM')
    GROUP BY community_id HAVING count(*) = 1
)
UPDATE pubg_kill_competitions e SET community_game_id = u.game_id FROM unambiguous u
WHERE e.community_id = u.community_id AND e.community_game_id IS NULL;
COMMIT;

-- Resolve these rows using evidence before Phase 2; never fill every row with KAKAO.
SELECT id, community_id, community_member_id, external_user_id
FROM community_member_accounts WHERE provider = 'PUBG' AND platform IS NULL ORDER BY id;
SELECT 'pubg_bingo_events' AS table_name, id, community_id FROM pubg_bingo_events WHERE community_game_id IS NULL
UNION ALL
SELECT 'pubg_kill_competitions', id, community_id FROM pubg_kill_competitions WHERE community_game_id IS NULL;
-- Historical time_survived / road_kills remain NULL (unknown); no fabricated backfill.
