-- PostgreSQL 수동 마이그레이션. 운영 DB에는 이 작업에서 실행하지 않는다.
-- 기존 데이터/접속/연결 시각을 추측하여 채우지 않는다. 애플리케이션 배포 전에 적용한다.
BEGIN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE user_external_accounts ADD COLUMN IF NOT EXISTS linked_at TIMESTAMP WITH TIME ZONE;
COMMIT;

-- 큰 테이블의 쓰기 차단을 줄이기 위해 트랜잭션 밖에서 실행한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_created_id ON users (created_at DESC, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_last_login_id ON users (last_login_at DESC NULLS LAST, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_active_last_active ON users (last_active_at) WHERE status = 'ACTIVE';
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_community_users_user_ended ON community_users (user_id, ended_at);
