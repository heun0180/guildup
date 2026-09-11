-- 기존 수동 DDL 관리 방식. 운영 DB 적용용이며 자동 실행하지 않는다.
-- 로컬 ddl-auto=update 환경은 JPA 엔티티에서 테이블/인덱스를 생성한다.
BEGIN;
CREATE TABLE IF NOT EXISTS community_notices (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    is_important BOOLEAN NOT NULL DEFAULT FALSE,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_community_notices_feed
    ON community_notices (community_id, is_pinned DESC, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS community_events (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(30) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_community_events_dates CHECK (end_at >= start_at)
);
CREATE INDEX IF NOT EXISTS idx_community_events_start ON community_events (community_id, start_at, id);
CREATE INDEX IF NOT EXISTS idx_community_events_end ON community_events (community_id, end_at);
COMMIT;
