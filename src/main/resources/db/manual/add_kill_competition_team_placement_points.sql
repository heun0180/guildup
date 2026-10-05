-- Hibernate 자동 스키마 변경을 사용하지 않는 환경에서는 배포 전에 실행한다.
-- NULL은 기존 정책의 팀을 뜻한다. 애플리케이션 시작 시 공통 계산기로 개인/팀 점수만 전환한다.
-- 개인 킬 수, 참가 시각, 경기 등수 원본, 활동 점수 원장과 빙고 이력은 유지한다.
ALTER TABLE pubg_kill_competition_teams ADD COLUMN IF NOT EXISTS interim_placement_points INTEGER;
ALTER TABLE pubg_kill_competition_teams ADD COLUMN IF NOT EXISTS final_placement_points INTEGER;
