-- 배포 전에 실행한다. 임시 테이블을 만들지 않으며 기존 커뮤니티 데이터는 유지한다.
BEGIN;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS creation_request_key VARCHAR(100);
ALTER TABLE communities ADD COLUMN IF NOT EXISTS creation_request_hash VARCHAR(64);
ALTER TABLE communities ADD COLUMN IF NOT EXISTS invite_code VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS uk_community_creation_request ON communities (creation_request_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_community_invite_code ON communities (invite_code);
-- 현재 엔티티에 존재하는 제약을 운영 DB에서도 보장한다. 기존 중복이 있다면 먼저 조사해야 한다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_discord_connection_guild ON discord_community_connections (discord_guild_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_discord_connection_community ON discord_community_connections (community_id);
ALTER TABLE community_users ADD COLUMN IF NOT EXISTS community_member_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_community_user_member ON community_users (community_member_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_community_user_member'
                   AND conrelid = 'community_users'::regclass) THEN
        ALTER TABLE community_users ADD CONSTRAINT fk_community_user_member
            FOREIGN KEY (community_member_id) REFERENCES community_members (id) ON DELETE SET NULL;
    END IF;
END $$;

-- 기존 미연결 커뮤니티도 Discord 없이 자체 기능을 쓸 수 있게 내부 연결을 보충한다.
-- 검증된 Discord 사용자 계정으로 이미 매칭되는 클랜원은 재사용한다. 이름으로는 추측하지 않는다.
DO $$
DECLARE
    membership RECORD;
    member_id BIGINT;
BEGIN
    FOR membership IN
        SELECT cu.id, cu.community_id, u.nickname, account.external_user_id, account.external_username
        FROM community_users cu JOIN users u ON u.id = cu.user_id
        LEFT JOIN user_external_accounts account ON account.user_id = u.id AND account.provider = 'DISCORD'
        WHERE cu.community_member_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM discord_community_connections dc WHERE dc.community_id = cu.community_id)
        FOR UPDATE OF cu
    LOOP
        member_id := NULL;
        SELECT a.community_member_id INTO member_id FROM community_member_accounts a
        WHERE a.community_id = membership.community_id AND a.provider = 'DISCORD'
          AND a.external_user_id = membership.external_user_id LIMIT 1;
        IF member_id IS NULL THEN
            INSERT INTO community_members (community_id, nickname, status, created_at, updated_at)
            VALUES (membership.community_id, membership.nickname, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id INTO member_id;
            IF membership.external_user_id IS NOT NULL THEN
                INSERT INTO community_member_accounts (community_id, community_member_id, provider, external_user_id, external_username)
                VALUES (membership.community_id, member_id, 'DISCORD', membership.external_user_id, membership.external_username);
            END IF;
        END IF;
        UPDATE community_users SET community_member_id = member_id WHERE id = membership.id;
    END LOOP;
END $$;
COMMIT;
