-- 치즈 클랜 1주년 빙고(event 5)의 확정된 설정 오류만 보정한다.
-- 진행도와 처리 원장은 삭제하지 않는다.
BEGIN;

UPDATE bingo_cells
SET options_json = COALESCE(options_json, '{}'::jsonb) - 'gameMode' - 'clanPlayRequired'
WHERE bingo_event_id = 5
  AND mission_type <> 'KILL_BET_WIN';

UPDATE bingo_cells
SET options_json = jsonb_set(COALESCE(options_json, '{}'::jsonb), '{distance}', '200'::jsonb, true)
WHERE bingo_event_id = 5
  AND mission_type = 'LONG_DISTANCE_KILL';

UPDATE bingo_cells
SET target_value = 5,
    options_json = jsonb_set(COALESCE(options_json, '{}'::jsonb), '{weapon}', '"VSS"'::jsonb, true)
WHERE bingo_event_id = 5
  AND mission_type = 'WEAPON_KILLS'
  AND lower(trim(options_json ->> 'weapon')) IN ('vss', 'weapvss_c');

COMMIT;
