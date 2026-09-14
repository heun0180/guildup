-- 기존 수동 DDL 관리 방식. 운영 PostgreSQL DB에 서버 배포 전에 한 번 적용한다.
-- 로컬 ddl-auto=update 및 테스트 create-drop 환경은 JPA 엔티티에서 동일 스키마를 생성한다.
BEGIN;

CREATE TABLE IF NOT EXISTS community_attendances (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    community_member_id BIGINT NOT NULL REFERENCES community_members(id) ON DELETE CASCADE,
    attendance_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_community_attendance_member_date
        UNIQUE (community_member_id, attendance_date)
);
CREATE INDEX IF NOT EXISTS idx_community_attendance_community_date
    ON community_attendances (community_id, attendance_date);

CREATE TABLE IF NOT EXISTS community_score_history (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    community_member_id BIGINT NOT NULL REFERENCES community_members(id) ON DELETE CASCADE,
    score_change INTEGER NOT NULL,
    score_type VARCHAR(50) NOT NULL,
    description VARCHAR(200) NOT NULL,
    reference_type VARCHAR(50) NOT NULL,
    reference_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_community_score_history_positive CHECK (score_change > 0),
    CONSTRAINT uk_community_score_history_reference
        UNIQUE (community_member_id, score_type, reference_type, reference_id)
);
CREATE INDEX IF NOT EXISTS idx_community_score_history_member_created
    ON community_score_history (community_member_id, created_at, id);

CREATE TABLE IF NOT EXISTS community_member_scores (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    community_member_id BIGINT NOT NULL REFERENCES community_members(id) ON DELETE CASCADE,
    total_score INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_community_member_score_nonnegative CHECK (total_score >= 0),
    CONSTRAINT uk_community_member_score_member UNIQUE (community_member_id)
);
CREATE INDEX IF NOT EXISTS idx_community_member_score_ranking
    ON community_member_scores (community_id, total_score DESC, community_member_id ASC);

COMMIT;
