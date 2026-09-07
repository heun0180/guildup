-- 2026-09-06 사용자 승인 후 실행 완료. 재실행용이 아닌 복구 기록이다.
-- 기존 Community 1의 관리자 권한을 User 1에게 부여한다.
-- Community 2 및 기존 Discord 연결/클랜원 데이터는 변경하지 않는다.
BEGIN;
DO $$
BEGIN
    PERFORM id FROM communities WHERE id IN (1, 2) ORDER BY id FOR UPDATE;
    IF NOT EXISTS (
        SELECT 1 FROM discord_community_connections
        WHERE community_id = 1 AND discord_guild_id = '1401450300136751234'
    ) OR NOT EXISTS (
        SELECT 1 FROM community_users
        WHERE community_id = 2 AND user_id = 1 AND role = 'OWNER'
    ) OR EXISTS (
        SELECT 1 FROM community_users WHERE community_id = 1
    ) THEN
        RAISE EXCEPTION '복구 전제 조건이 바뀌었습니다. Community와 사용자 관계를 다시 확인하세요.';
    END IF;

    INSERT INTO community_users (community_id, user_id, role)
    VALUES (1, 1, 'OWNER');
END $$;
COMMIT;
