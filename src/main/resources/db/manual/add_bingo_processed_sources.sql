-- GuildUp 내부 콘텐츠 결과의 빙고 반영 원장.
-- 기존 bingo_processed_matches는 변경하지 않아 운영 데이터 호환성을 유지한다.

CREATE TABLE IF NOT EXISTS bingo_processed_sources (
    id BIGSERIAL PRIMARY KEY,
    bingo_event_id BIGINT NOT NULL REFERENCES bingo_events(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL REFERENCES bingo_participants(id) ON DELETE CASCADE,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_bingo_processed_source UNIQUE (
        bingo_event_id, participant_id, source_type, source_id
    ),
    CONSTRAINT ck_bingo_processed_source_type CHECK (
        source_type IN ('PUBG_MATCH', 'KILL_COMPETITION')
    )
);

CREATE INDEX IF NOT EXISTS idx_bingo_processed_source_lookup
    ON bingo_processed_sources (source_type, source_id);
