-- 운영 이벤트 36의 장거리 킬 셀은 제목에 200m가 명시되어 있지만,
-- 거리 옵션 필수 검증 도입 전에 생성되어 options_json.distance가 누락됐다.
-- 진행도나 처리 원장은 건드리지 않는다. 다음 집계의 DB Fact 전체 재계산으로 반영한다.
BEGIN;

UPDATE bingo_cells
SET options_json = jsonb_set(COALESCE(options_json, '{}'::jsonb), '{distance}', '200'::jsonb, true)
WHERE id = 224
  AND bingo_event_id = 36
  AND mission_type = 'LONG_DISTANCE_KILL'
  AND custom_title = '200m 이상 장거리 5킬'
  AND NOT (COALESCE(options_json, '{}'::jsonb) ? 'distance');

COMMIT;
