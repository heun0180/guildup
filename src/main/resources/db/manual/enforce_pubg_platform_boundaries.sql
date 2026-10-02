-- Phase 2 / fresh-install finalization: application writers MUST be stopped.
-- Run after add_pubg_platform_boundaries.sql, with psql -v ON_ERROR_STOP=1.
-- Any unresolved/invalid row aborts the whole transaction BEFORE dropping old constraints.
BEGIN;
LOCK TABLE community_member_accounts, community_games, pubg_matches,
           pubg_bingo_events, pubg_kill_competitions IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM community_member_accounts WHERE
        (provider = 'PUBG' AND (platform IS NULL OR platform NOT IN ('KAKAO', 'STEAM')))
        OR (provider <> 'PUBG' AND platform IS NOT NULL)) THEN
        RAISE EXCEPTION 'Unresolved/invalid account platform: audit community_member_accounts before enforcement';
    END IF;
    IF EXISTS (SELECT 1 FROM pubg_bingo_events e LEFT JOIN community_games g ON g.id = e.community_game_id
        WHERE g.id IS NULL OR g.community_id <> e.community_id
              OR g.game_type NOT IN ('BATTLEGROUNDS_KAKAO', 'BATTLEGROUNDS_STEAM'))
       OR EXISTS (SELECT 1 FROM pubg_kill_competitions e LEFT JOIN community_games g ON g.id = e.community_game_id
        WHERE g.id IS NULL OR g.community_id <> e.community_id
              OR g.game_type NOT IN ('BATTLEGROUNDS_KAKAO', 'BATTLEGROUNDS_STEAM')) THEN
        RAISE EXCEPTION 'Unresolved/invalid event CommunityGame: audit bingo/competition origin before enforcement';
    END IF;
END $$;

-- Discover legacy unique constraints/indexes by their actual columns, not assumed names.
DO $$ DECLARE item record; BEGIN
    FOR item IN
        SELECT c.conname, c.conrelid::regclass AS relation
        FROM pg_constraint c
        WHERE c.contype = 'u' AND (
            (c.conrelid = 'community_member_accounts'::regclass AND
              (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM unnest(c.conkey) k JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k)
              IN (ARRAY['community_member_id','provider'], ARRAY['community_id','external_user_id','provider']))
            OR (c.conrelid = 'pubg_matches'::regclass AND
              (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM unnest(c.conkey) k JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k) = ARRAY['match_id']))
    LOOP EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', item.relation, item.conname); END LOOP;
    FOR item IN
        SELECT i.indexrelid::regclass AS index_name FROM pg_index i
        WHERE i.indisunique AND NOT i.indisprimary AND i.indpred IS NULL AND i.indexprs IS NULL
          AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid=i.indexrelid)
          AND ((i.indrelid = 'community_member_accounts'::regclass AND
              (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM unnest(i.indkey) k JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k)
              IN (ARRAY['community_member_id','provider'], ARRAY['community_id','external_user_id','provider']))
            OR (i.indrelid = 'pubg_matches'::regclass AND
              (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM unnest(i.indkey) k JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k) = ARRAY['match_id']))
    LOOP EXECUTE format('DROP INDEX %s', item.index_name); END LOOP;
END $$;

-- Ordinary composites enforce PUBG uniqueness; partial indexes preserve non-PUBG NULL uniqueness.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='community_member_accounts'::regclass AND conname='uk_community_member_account_platform') THEN
        ALTER TABLE community_member_accounts ADD CONSTRAINT uk_community_member_account_platform
            UNIQUE (community_member_id, provider, platform);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='community_member_accounts'::regclass AND conname='uk_community_member_account_platform_user') THEN
        ALTER TABLE community_member_accounts ADD CONSTRAINT uk_community_member_account_platform_user
            UNIQUE (community_id, provider, platform, external_user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='community_member_accounts'::regclass AND conname='ck_community_member_account_platform') THEN
        ALTER TABLE community_member_accounts ADD CONSTRAINT ck_community_member_account_platform
            CHECK ((provider='PUBG' AND platform IS NOT NULL AND platform IN ('KAKAO','STEAM'))
                OR (provider<>'PUBG' AND platform IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='pubg_matches'::regclass AND conname='uk_pubg_matches_shard_match_id') THEN
        ALTER TABLE pubg_matches ADD CONSTRAINT uk_pubg_matches_shard_match_id UNIQUE (shard, match_id);
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_community_member_account_non_pubg
    ON community_member_accounts (community_member_id, provider) WHERE provider <> 'PUBG';
CREATE UNIQUE INDEX IF NOT EXISTS uk_community_member_account_non_pubg_user
    ON community_member_accounts (community_id, provider, external_user_id) WHERE provider <> 'PUBG';

-- Ensure both parent identity and event ownership are enforced, including fresh installs.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='community_games'::regclass AND conname='uk_community_game_id_community') THEN
        ALTER TABLE community_games ADD CONSTRAINT uk_community_game_id_community UNIQUE (id, community_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='pubg_bingo_events'::regclass AND conname='fk_pubg_bingo_event_game_owner') THEN
        ALTER TABLE pubg_bingo_events ADD CONSTRAINT fk_pubg_bingo_event_game_owner
            FOREIGN KEY (community_game_id, community_id) REFERENCES community_games(id, community_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='pubg_kill_competitions'::regclass AND conname='fk_pubg_kill_competition_game_owner') THEN
        ALTER TABLE pubg_kill_competitions ADD CONSTRAINT fk_pubg_kill_competition_game_owner
            FOREIGN KEY (community_game_id, community_id) REFERENCES community_games(id, community_id);
    END IF;
END $$;
ALTER TABLE pubg_bingo_events ALTER COLUMN community_game_id SET NOT NULL;
ALTER TABLE pubg_kill_competitions ALTER COLUMN community_game_id SET NOT NULL;
COMMIT;
