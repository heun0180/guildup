-- Read-only audit. Run AFTER Phase 1; review before Phase 2. Never infers or updates ambiguous data.
SELECT a.id, a.community_id, a.community_member_id, a.external_user_id, a.platform,
       (SELECT string_agg(g.id::text || ':' || g.game_type, ', ' ORDER BY g.id)
        FROM community_games g WHERE g.community_id=a.community_id
          AND g.game_type IN ('BATTLEGROUNDS_KAKAO','BATTLEGROUNDS_STEAM')) AS configured_pubg_games
FROM community_member_accounts a
WHERE (a.provider='PUBG' AND (a.platform IS NULL OR a.platform NOT IN ('KAKAO','STEAM')))
   OR (a.provider<>'PUBG' AND a.platform IS NOT NULL)
ORDER BY a.community_id, a.id;

SELECT 'pubg_bingo_events' AS table_name, e.id, e.community_id, e.community_game_id, g.game_type
FROM pubg_bingo_events e LEFT JOIN community_games g ON g.id=e.community_game_id
WHERE g.id IS NULL OR g.community_id<>e.community_id OR g.game_type NOT IN ('BATTLEGROUNDS_KAKAO','BATTLEGROUNDS_STEAM')
UNION ALL
SELECT 'pubg_kill_competitions', e.id, e.community_id, e.community_game_id, g.game_type
FROM pubg_kill_competitions e LEFT JOIN community_games g ON g.id=e.community_game_id
WHERE g.id IS NULL OR g.community_id<>e.community_id OR g.game_type NOT IN ('BATTLEGROUNDS_KAKAO','BATTLEGROUNDS_STEAM');

-- Historical flags alone cannot prove that actual Telemetry was fetched. Investigate; do not mass-reset progress.
SELECT id, shard, match_id, telemetry_url, telemetry_loaded, telemetry_fact_version
FROM pubg_matches WHERE telemetry_loaded=true
 AND (telemetry_url IS NULL OR telemetry_url NOT LIKE 'https://telemetry-cdn.pubg.com/%');
SELECT shard, count(*) FROM pubg_matches GROUP BY shard;
SELECT count(*) AS historical_players_with_unknown_original_stats
FROM pubg_match_players WHERE time_survived IS NULL OR road_kills IS NULL;

-- A snapshot differing from the current account can also be a legitimate nickname/account change.
-- These rows require provenance checks, not automatic replacement of past competition participants.
SELECT c.id AS competition_id, g.game_type, p.id AS participant_id, p.community_member_id,
       p.pubg_account_id AS snapshot_account_id, a.external_user_id AS current_platform_account_id
FROM pubg_kill_competition_participants p
JOIN pubg_kill_competitions c ON c.id=p.competition_id
JOIN community_games g ON g.id=c.community_game_id
LEFT JOIN community_member_accounts a ON a.community_member_id=p.community_member_id
 AND a.community_id=c.community_id AND a.provider='PUBG'
 AND a.platform=CASE g.game_type WHEN 'BATTLEGROUNDS_KAKAO' THEN 'KAKAO' WHEN 'BATTLEGROUNDS_STEAM' THEN 'STEAM' END
WHERE a.id IS NULL OR a.external_user_id<>p.pubg_account_id;
