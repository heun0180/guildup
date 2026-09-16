ALTER TABLE community_users ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS bingo_events (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    created_by_community_user_id BIGINT NOT NULL REFERENCES community_users(id),
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    board_size INTEGER NOT NULL CHECK (board_size IN (3,4,5)),
    target_lines INTEGER NOT NULL CHECK (target_lines > 0),
    blackout_enabled BOOLEAN NOT NULL,
    allow_late_join BOOLEAN NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_aggregated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_bingo_event_time CHECK (starts_at < ends_at)
);
CREATE INDEX IF NOT EXISTS idx_bingo_event_community_status ON bingo_events(community_id,status,starts_at,ends_at);

CREATE TABLE IF NOT EXISTS bingo_cells (
    id BIGSERIAL PRIMARY KEY,
    bingo_event_id BIGINT NOT NULL REFERENCES bingo_events(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    mission_type VARCHAR(40) NOT NULL,
    aggregation_type VARCHAR(30) NOT NULL,
    comparison_operator VARCHAR(30) NOT NULL,
    target_value NUMERIC(16,3) NOT NULL,
    occurrence_target INTEGER,
    options_json JSONB,
    custom_title VARCHAR(120),
    CONSTRAINT uk_bingo_cell_position UNIQUE(bingo_event_id,position)
);

CREATE TABLE IF NOT EXISTS bingo_participants (
    id BIGSERIAL PRIMARY KEY,
    bingo_event_id BIGINT NOT NULL REFERENCES bingo_events(id) ON DELETE CASCADE,
    community_user_id BIGINT NOT NULL REFERENCES community_users(id),
    community_member_id BIGINT REFERENCES community_members(id),
    pubg_account_id VARCHAR(255),
    pubg_nickname VARCHAR(255),
    joined_at TIMESTAMPTZ NOT NULL,
    eligible_from TIMESTAMPTZ NOT NULL,
    line_count INTEGER NOT NULL DEFAULT 0,
    target_lines_completed_at TIMESTAMPTZ,
    blackout_completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_bingo_participant_user UNIQUE(bingo_event_id,community_user_id)
);

CREATE TABLE IF NOT EXISTS bingo_progress (
    id BIGSERIAL PRIMARY KEY,
    participant_id BIGINT NOT NULL REFERENCES bingo_participants(id) ON DELETE CASCADE,
    bingo_cell_id BIGINT NOT NULL REFERENCES bingo_cells(id) ON DELETE CASCADE,
    current_value NUMERIC(18,3) NOT NULL DEFAULT 0,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ,
    evidence_match_id VARCHAR(255),
    evidence_event_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_bingo_progress_participant_cell UNIQUE(participant_id,bingo_cell_id)
);

CREATE TABLE IF NOT EXISTS bingo_processed_matches (
    id BIGSERIAL PRIMARY KEY,
    bingo_event_id BIGINT NOT NULL REFERENCES bingo_events(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL REFERENCES bingo_participants(id) ON DELETE CASCADE,
    match_id VARCHAR(255) NOT NULL,
    match_started_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_bingo_processed_event_player_match UNIQUE(bingo_event_id,participant_id,match_id)
);

CREATE TABLE IF NOT EXISTS bingo_line_completions (
    id BIGSERIAL PRIMARY KEY,
    participant_id BIGINT NOT NULL REFERENCES bingo_participants(id) ON DELETE CASCADE,
    line_key VARCHAR(20) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_bingo_line_participant_key UNIQUE(participant_id,line_key)
);
