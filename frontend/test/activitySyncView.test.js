import test from "node:test";
import assert from "node:assert/strict";
import { activitySyncView, formatActivityDateTime } from "../src/activityView.js";

test("24시간 제한 중에는 활동 새로 조회 버튼을 비활성화한다", () => {
  const view = activitySyncView({
    status: "SUCCESS",
    syncAvailable: false,
    lastSuccessfulSyncAt: "2026-09-07T15:30:00Z",
    nextSyncAvailableAt: "2026-09-08T15:30:00Z",
  });

  assert.equal(view.buttonLabel, "활동 새로 조회");
  assert.equal(view.buttonDisabled, true);
  assert.equal(view.title, "최신 활동 데이터입니다.");
});

test("마지막 조회와 다음 조회 가능 시간을 한국 시간으로 표시한다", () => {
  assert.match(formatActivityDateTime("2026-09-07T15:30:00Z"), /2026.*09.*08.*(?:00|24):30/);
  assert.match(formatActivityDateTime("2026-09-08T15:30:00Z"), /2026.*09.*09.*(?:00|24):30/);
});

test("한 번도 조회하지 않았다면 활동 조회 버튼을 활성화한다", () => {
  const view = activitySyncView({ status: "NEVER_SYNCED", syncAvailable: true });
  assert.equal(view.buttonLabel, "활동 조회");
  assert.equal(view.buttonDisabled, false);
});
