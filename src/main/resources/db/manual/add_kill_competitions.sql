-- PostgreSQL 운영 DB에 킬내기 기능을 수동 반영할 때 사용하는 스키마다.
-- 애플리케이션은 현재 spring.jpa.hibernate.ddl-auto=update를 사용하지만 운영 반영 전 검토용으로 유지한다.

CREATE TABLE IF NOT EXISTS kill_competitions (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    created_by_member_id BIGINT NOT NULL REFERENCES community_members(id),
    title VARCHAR(100) NOT NULL,
    game_mode VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    recruitment_closed_at TIMESTAMPTZ,
    last_interim_calculated_at TIMESTAMPTZ,
    interim_calculation_started_at TIMESTAMPTZ,
    finalization_started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_kill_competition_game_mode CHECK (game_mode IN ('SOLO', 'DUO', 'SQUAD')),
    CONSTRAINT ck_kill_competition_status CHECK (status IN ('RECRUITING', 'READY', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_kill_competition_community_status
    ON kill_competitions (community_id, status, ends_at);

CREATE TABLE IF NOT EXISTS kill_competition_teams (
    id BIGSERIAL PRIMARY KEY,
    competition_id BIGINT NOT NULL REFERENCES kill_competitions(id) ON DELETE CASCADE,
    team_name VARCHAR(40) NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT uk_kill_competition_team_order UNIQUE (competition_id, display_order)
);

CREATE TABLE IF NOT EXISTS kill_competition_participants (
    id BIGSERIAL PRIMARY KEY,
    competition_id BIGINT NOT NULL REFERENCES kill_competitions(id) ON DELETE CASCADE,
    community_member_id BIGINT NOT NULL REFERENCES community_members(id),
    team_id BIGINT REFERENCES kill_competition_teams(id),
    pubg_account_id VARCHAR(255) NOT NULL,
    pubg_nickname VARCHAR(255) NOT NULL,
    interim_kills INTEGER NOT NULL DEFAULT 0,
    interim_match_count INTEGER NOT NULL DEFAULT 0,
    final_kills INTEGER,
    final_match_count INTEGER,
    CONSTRAINT uk_kill_competition_participant UNIQUE (competition_id, community_member_id)
);

CREATE TABLE IF NOT EXISTS kill_competition_match_results (
    id BIGSERIAL PRIMARY KEY,
    competition_id BIGINT NOT NULL REFERENCES kill_competitions(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL REFERENCES kill_competition_participants(id) ON DELETE CASCADE,
    match_id VARCHAR(255) NOT NULL,
    match_started_at TIMESTAMPTZ NOT NULL,
    kills INTEGER NOT NULL,
    CONSTRAINT uk_kill_competition_match_participant UNIQUE (competition_id, match_id, participant_id)
);

CREATE INDEX IF NOT EXISTS idx_kill_competition_match
    ON kill_competition_match_results (competition_id, match_id);
