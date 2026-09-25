import test from "node:test";
import assert from "node:assert/strict";
import { canCancelKillGame, canEndKillGame, canManageKillGame, remainingLabel, statusLabel, statusMessage, toDateTimeLocal } from "../src/killCompetitionView.js";

test("킬내기 상태 문구를 사용자 행동에 맞게 표시한다", () => {
  assert.equal(statusLabel("READY"), "시작 준비");
  assert.match(statusMessage("ENDED"), /결과를 발표/);
  assert.match(statusMessage("RESULT_PENDING"), /30분 후/);
});

test("킬내기 관리 권한은 서버의 통합 권한 값을 사용하고 기존 응답도 호환한다", () => {
  assert.equal(canManageKillGame({ canManageKillGame: true }), true);
  assert.equal(canManageKillGame({ canManageKillGame: false, creatorView: true }), false);
  assert.equal(canManageKillGame({ administratorView: true }), true);
  assert.equal(canManageKillGame({ creatorView: false, administratorView: false }), false);
  assert.equal(canManageKillGame({ canManageKillGame: false }, "ADMIN"), true);
  assert.equal(canManageKillGame({ canManageKillGame: false }, "OWNER"), true);
  assert.equal(canManageKillGame({ canManageKillGame: false }, "MEMBER"), false);
});

test("관리자는 상태에 맞는 종료 또는 취소 동작을 사용할 수 있다", () => {
  const now = Date.parse("2026-09-15T12:00:00Z");
  assert.equal(canEndKillGame({ canManageKillGame: true, status: "IN_PROGRESS", endsAt: "2026-09-15T13:00:00Z" }, now), true);
  assert.equal(canEndKillGame({ canManageKillGame: false, status: "IN_PROGRESS", endsAt: "2026-09-15T13:00:00Z" }, now), false);
  assert.equal(canEndKillGame({ canManageKillGame: true, status: "ENDED", endsAt: "2026-09-15T12:00:00Z" }, now), false);
  assert.equal(canCancelKillGame({ administratorView: true, status: "RECRUITING" }), true);
  assert.equal(canCancelKillGame({ canManageKillGame: true, status: "IN_PROGRESS" }), false);
});

test("종료 시각을 datetime-local 기본값으로 변환한다", () => {
  assert.match(toDateTimeLocal("2026-09-15T12:00:00Z"), /^2026-09-15T\d{2}:00$/);
});

test("남은 시간은 전달받은 서버 기준 시각으로 계산한다", () => {
  assert.equal(remainingLabel("2026-09-15T12:01:05Z", Date.parse("2026-09-15T12:00:00Z")), "1분 5초");
  assert.equal(remainingLabel("2026-09-15T12:00:00Z", Date.parse("2026-09-15T12:00:00Z")), "종료됨");
});
