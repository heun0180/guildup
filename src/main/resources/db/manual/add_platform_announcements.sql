-- 운영 적용 전 검토/실행하는 PostgreSQL 수동 DDL. 자동 실행하지 않는다.
BEGIN;
CREATE TABLE IF NOT EXISTS platform_announcements (
    id BIGSERIAL PRIMARY KEY,
    created_by BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(30) NOT NULL,
    is_important BOOLEAN NOT NULL DEFAULT FALSE,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    is_popup BOOLEAN NOT NULL DEFAULT FALSE,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    publish_start_at TIMESTAMPTZ,
    publish_end_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    target_type VARCHAR(30) NOT NULL DEFAULT 'ALL',
    target_game_type VARCHAR(50),
    target_community_id BIGINT REFERENCES communities(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_platform_announcements_type CHECK (type IN ('NOTICE','UPDATE','MAINTENANCE','INCIDENT','EVENT')),
    CONSTRAINT chk_platform_announcements_period CHECK (publish_start_at IS NULL OR publish_end_at IS NULL OR publish_end_at > publish_start_at),
    CONSTRAINT chk_platform_announcements_popup CHECK (NOT is_popup OR is_important),
    CONSTRAINT chk_platform_announcements_publication CHECK (NOT is_published OR published_at IS NOT NULL),
    CONSTRAINT chk_platform_announcements_target CHECK (
        (target_type = 'ALL' AND target_game_type IS NULL AND target_community_id IS NULL) OR
        (target_type = 'GAME' AND target_game_type IN ('BATTLEGROUNDS_KAKAO','BATTLEGROUNDS_STEAM') AND target_game_type IS NOT NULL AND target_community_id IS NULL) OR
        (target_type = 'COMMUNITY' AND target_game_type IS NULL AND target_community_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_platform_announcements_feed
    ON platform_announcements (target_type, is_published, is_pinned DESC, is_important DESC, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS platform_announcement_reads (
    id BIGSERIAL PRIMARY KEY,
    announcement_id BIGINT NOT NULL REFERENCES platform_announcements(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ,
    popup_confirmed_at TIMESTAMPTZ,
    CONSTRAINT uk_platform_announcement_reads_user UNIQUE (announcement_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_platform_announcement_reads_user
    ON platform_announcement_reads (user_id, announcement_id);
COMMIT;
