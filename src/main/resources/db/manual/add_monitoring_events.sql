CREATE TABLE IF NOT EXISTS monitoring_events (
    id BIGSERIAL PRIMARY KEY,
    severity VARCHAR(10) NOT NULL,
    category VARCHAR(32) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    community_id BIGINT NULL,
    user_id BIGINT NULL,
    reference_id VARCHAR(160) NULL,
    metadata JSONB NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_monitoring_events_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX IF NOT EXISTS idx_monitoring_events_occurred_at
    ON monitoring_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_monitoring_events_severity_occurred_at
    ON monitoring_events (severity, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_monitoring_events_category_code_occurred_at
    ON monitoring_events (category, event_code, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_monitoring_events_community_occurred_at
    ON monitoring_events (community_id, occurred_at DESC)
    WHERE community_id IS NOT NULL;

-- 애플리케이션 scheduler가 30일을 기본값으로, 500건씩 나누어 삭제한다.
