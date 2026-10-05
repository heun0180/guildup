-- H08: 미수집 경기 ID를 보관하여 Player API 최근 목록에서 빠진 뒤에도 재시도한다.
CREATE TABLE IF NOT EXISTS pubg_bingo_pending_matches (
    bingo_event_id BIGINT NOT NULL REFERENCES pubg_bingo_events(id) ON DELETE CASCADE,
    match_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (bingo_event_id, match_id)
);
