-- 최종 결과 집계 작업의 소유권을 timestamp 정밀도와 무관한 UUID로 식별한다.
-- 기존 RESULT_PENDING 행은 NULL을 허용하며, 기존 finalization_started_at이 만료되면
-- 스케줄러가 새 UUID token을 발급해 자동으로 다시 처리한다.

ALTER TABLE kill_competitions
    ADD COLUMN IF NOT EXISTS finalization_claim_token UUID;
