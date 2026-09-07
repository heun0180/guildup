import test from "node:test";
import assert from "node:assert/strict";
import { activityStatus, formatGameMode, formatRelativeDays } from "../src/activityView.js";

test("활동 상태를 운영진이 이해할 수 있는 문구로 표시한다", () => {
  assert.equal(activityStatus("ACTIVE").label, "정상");
  assert.equal(activityStatus("NO_CLAN_ACTIVITY").label, "활동 없음");
  assert.equal(activityStatus("NO_RECENT_MATCHES").label, "최근 게임 없음");
  assert.equal(activityStatus("ACCOUNT_VERIFICATION_REQUIRED").label, "계정 확인 필요");
});

test("최근 활동 시각과 PUBG 게임 모드를 화면용 문구로 바꾼다", () => {
  const now = new Date("2026-09-07T12:00:00Z");
  assert.equal(formatRelativeDays("2026-09-05T11:00:00Z", now), "2일 전");
  assert.equal(formatRelativeDays(null, now), "-");
  assert.equal(formatGameMode("squad"), "스쿼드");
  assert.equal(formatGameMode("squad-fpp"), "스쿼드 FPP");
});
