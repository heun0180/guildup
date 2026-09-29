-- 기존 킬내기에 점수 설정과 경기별 점수 근거를 추가한다.
-- 기존 행은 1킬=1점, 등수 점수 미사용으로 보정되어 종전 결과가 유지된다.

ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS kill_point INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS placement_point_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS first_place_point INTEGER NOT NULL DEFAULT 5;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS second_place_point INTEGER NOT NULL DEFAULT 4;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS third_place_point INTEGER NOT NULL DEFAULT 3;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS fourth_fifth_place_point INTEGER NOT NULL DEFAULT 2;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS sixth_tenth_place_point INTEGER NOT NULL DEFAULT 1;
-- 개별 등수 컬럼은 nullable로 추가한다. 기존 행의 NULL은 위의 구간 점수를 fallback으로 사용한다.
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS fourth_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS fifth_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS sixth_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS seventh_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS eighth_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS ninth_place_point INTEGER;
ALTER TABLE pubg_kill_competitions ADD COLUMN IF NOT EXISTS tenth_place_point INTEGER;

ALTER TABLE pubg_kill_competition_participants ADD COLUMN IF NOT EXISTS interim_points INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pubg_kill_competition_participants ADD COLUMN IF NOT EXISTS final_points INTEGER;

UPDATE pubg_kill_competition_participants participant
SET interim_points = participant.interim_kills * competition.kill_point
FROM pubg_kill_competitions competition
WHERE participant.competition_id = competition.id
  AND participant.interim_points = 0
  AND participant.interim_kills <> 0;

UPDATE pubg_kill_competition_participants participant
SET final_points = participant.final_kills * competition.kill_point
FROM pubg_kill_competitions competition
WHERE participant.competition_id = competition.id
  AND participant.final_kills IS NOT NULL
  AND participant.final_points IS NULL;

ALTER TABLE pubg_kill_competition_match_results ADD COLUMN IF NOT EXISTS placement INTEGER;
ALTER TABLE pubg_kill_competition_match_results ADD COLUMN IF NOT EXISTS kill_points INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pubg_kill_competition_match_results ADD COLUMN IF NOT EXISTS placement_points INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pubg_kill_competition_match_results ADD COLUMN IF NOT EXISTS total_points INTEGER NOT NULL DEFAULT 0;

UPDATE pubg_kill_competition_match_results result
SET kill_points = result.kills,
    placement_points = 0,
    total_points = result.kills
FROM pubg_kill_competitions competition
WHERE result.competition_id = competition.id
  AND competition.kill_point = 1
  AND competition.placement_point_enabled = FALSE
  AND result.placement IS NULL
  AND result.kill_points = 0
  AND result.placement_points = 0
  AND result.total_points = 0;
