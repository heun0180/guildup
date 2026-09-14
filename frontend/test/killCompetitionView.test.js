import test from "node:test";
import assert from "node:assert/strict";
import { remainingLabel, statusLabel, statusMessage } from "../src/killCompetitionView.js";

test("킬내기 상태 문구를 사용자 행동에 맞게 표시한다", () => {
  assert.equal(statusLabel("READY"), "시작 준비");
  assert.match(statusMessage("ENDED"), /결과를 발표/);
});

test("남은 시간은 전달받은 서버 기준 시각으로 계산한다", () => {
  assert.equal(remainingLabel("2026-09-15T12:01:05Z", Date.parse("2026-09-15T12:00:00Z")), "1분 5초");
  assert.equal(remainingLabel("2026-09-15T12:00:00Z", Date.parse("2026-09-15T12:00:00Z")), "종료됨");
});
