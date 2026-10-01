-- 운영 PostgreSQL에 새 애플리케이션 배포 전에 적용. 재실행 가능하며 기존 점수를 변경하지 않는다.
BEGIN;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS ranking_period_type VARCHAR(20) NOT NULL DEFAULT 'ALL_TIME';
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_community_ranking_period_type' AND conrelid = 'communities'::regclass) THEN
        ALTER TABLE communities ADD CONSTRAINT ck_community_ranking_period_type
            CHECK (ranking_period_type IN ('MONTHLY', 'QUARTERLY', 'ALL_TIME'));
    END IF;
END $$;
-- 출석 기간 조회는 기존 (community_id, attendance_date) 인덱스를 활용한다.
-- 시각 기준 킬내기/기타 원장 조회에만 필요한 인덱스를 추가한다.
CREATE INDEX IF NOT EXISTS idx_community_score_history_community_created
    ON community_score_history (community_id, created_at, community_member_id);
COMMIT;
