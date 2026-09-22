import test from "node:test";
import assert from "node:assert/strict";
import { bingoAggregationCooldown, bingoParticipantNames, currentBingoScreen, formatBingoKstDateTime, formatBingoLocalDateTime, groupManagedBingos, isBingoManagementView } from "../src/bingoView.js";

test("MEMBER는 관리 쿼리로 접근해도 관리 화면을 열 수 없다", () => {
  assert.equal(isBingoManagementView("MEMBER", "?communityId=1&view=manage"), false);
  assert.equal(isBingoManagementView("OWNER", "?communityId=1&view=manage"), true);
  assert.equal(isBingoManagementView("ADMIN", "?communityId=1&view=manage"), true);
});

test("현재 빙고 응답은 목록 없이 빙고판, 예정 안내, Empty State로 분기한다", () => {
  assert.equal(currentBingoScreen("ACTIVE"), "BOARD");
  assert.equal(currentBingoScreen("SCHEDULED"), "SCHEDULED");
  assert.equal(currentBingoScreen("NONE"), "EMPTY");
});

test("운영진 목록은 상태별 관리 영역으로 분리한다", () => {
  const groups = groupManagedBingos([
    { status: "ACTIVE" }, { status: "SETTLING" }, { status: "SCHEDULED" },
    { status: "DRAFT" }, { status: "COMPLETED" }, { status: "CANCELLED" },
  ]);
  assert.equal(groups.active.length, 2);
  assert.equal(groups.scheduled.length, 1);
  assert.equal(groups.draft.length, 1);
  assert.equal(groups.completed.length, 1);
  assert.equal(groups.cancelled.length, 1);
});

test("빙고 집계는 마지막 집계 후 30분 동안 비활성화된다", () => {
  const last = "2026-09-22T12:00:00Z";
  assert.deepEqual(bingoAggregationCooldown(null, Date.parse(last)), {
    disabled: false, remainingMs: 0, label: "빙고 집계",
  });
  assert.equal(bingoAggregationCooldown(last, Date.parse("2026-09-22T12:10:00Z")).label, "빙고 집계 (20:00)");
  assert.equal(bingoAggregationCooldown(last, Date.parse("2026-09-22T12:29:59.100Z")).label, "빙고 집계 (0:01)");
  assert.equal(bingoAggregationCooldown(last, Date.parse("2026-09-22T12:30:00Z")).disabled, false);
});

test("빙고 시간 미리보기는 자정과 정오를 구분한다", () => {
  assert.equal(formatBingoLocalDateTime("2026-09-24T00:00"), "2026. 09. 24. 00:00 (자정, 하루 시작)");
  assert.equal(formatBingoLocalDateTime("2026-09-24T12:00"), "2026. 09. 24. 12:00 (정오)");
  assert.equal(formatBingoLocalDateTime("2026-09-24T00:01"), "2026. 09. 24. 00:01");
  assert.match(formatBingoKstDateTime("2026-09-23T15:00:00Z"), /00:00$/);
  assert.match(formatBingoKstDateTime("2026-09-24T03:00:00Z"), /12:00$/);
});

test("참가자 이름은 PUBG 닉네임을 우선하고 Discord 닉네임을 함께 표시한다", () => {
  assert.deepEqual(bingoParticipantNames({ nickname: "애플", pubgNickname: "ApplePUBG" }), {
    primary: "ApplePUBG", secondary: "애플",
  });
  assert.deepEqual(bingoParticipantNames({ nickname: "애플", pubgNickname: null }), {
    primary: "애플", secondary: null,
  });
});
