import test from "node:test";
import assert from "node:assert/strict";
import {
  formatVoiceDuration,
  formatVoiceDateTime,
  loadVoiceActivityOnce,
  loadVoiceActivityPageData,
  voiceActivityView,
  VoiceActivityPeriod,
} from "../src/voiceActivityView.js";

test("로딩이 끝나면 API 목록을 화면 상태로 반환한다", async () => {
  const members = [{ communityMemberId: 1, totalSeconds: 600, currentlyConnected: false }];
  const result = await loadVoiceActivityPageData(async () => members);
  assert.equal(result.status, "loaded");
  assert.equal(result.members, members);
});

test("활동이 없는 목록과 빈 목록을 구분한다", () => {
  assert.equal(voiceActivityView([]).hasMembers, false);
  const view = voiceActivityView([{ totalSeconds: 0, currentlyConnected: false, discordUserId: "100" }]);
  assert.equal(view.hasMembers, true);
  assert.equal(view.hasActivity, false);
});

test("현재 접속 중인 클랜원을 집계한다", () => {
  const view = voiceActivityView([
    { totalSeconds: 60, currentlyConnected: true },
    { totalSeconds: 0, currentlyConnected: false },
  ]);
  assert.equal(view.connectedCount, 1);
  assert.equal(view.hasActivity, true);
});

test("API 오류를 오류 화면 상태로 반환한다", async () => {
  const failure = new Error("network");
  const result = await loadVoiceActivityPageData(async () => { throw failure; });
  assert.equal(result.status, "error");
  assert.equal(result.error, failure);
});

test("초 단위 합계를 읽기 쉬운 시분으로 표시한다", () => {
  assert.equal(formatVoiceDuration(45), "0분");
  assert.equal(formatVoiceDuration(180), "3분");
  assert.equal(formatVoiceDuration(2_880), "48분");
  assert.equal(formatVoiceDuration(45_120), "12시간 32분");
});

test("날짜를 월일 시분 형식으로 표시한다", () => {
  assert.equal(formatVoiceDateTime("2026-09-11T10:00:00Z"), "09/11 19:00");
  assert.equal(formatVoiceDateTime("2026-10-06T15:01:00Z"), "10/07 00:01");
});

test("같은 페이지 진입의 중복 effect는 API 요청을 공유한다", async () => {
  let calls = 0;
  const loader = async () => { calls += 1; return []; };
  await Promise.all([
    loadVoiceActivityOnce("strict-mode-test", loader),
    loadVoiceActivityOnce("strict-mode-test", loader),
  ]);
  assert.equal(calls, 1);
});

test("진행 중 요청도 기간별로 구분한다", async () => {
  const results = await Promise.all([
    loadVoiceActivityOnce("period-test", async () => ["ALL"], VoiceActivityPeriod.ALL),
    loadVoiceActivityOnce("period-test", async () => ["DAY"], VoiceActivityPeriod.DAY),
  ]);
  assert.deepEqual(results, [["ALL"], ["DAY"]]);
});

test("완료한 기간으로 돌아오면 최신 값을 다시 조회한다", async () => {
  let calls = 0;
  const loader = async () => ++calls;
  assert.equal(await loadVoiceActivityOnce("refresh-test", loader), 1);
  assert.equal(await loadVoiceActivityOnce("refresh-test", loader), 2);
});

test("실패한 요청은 다음 조회에서 재시도한다", async () => {
  await assert.rejects(loadVoiceActivityOnce("retry-test", async () => { throw new Error("failed"); }));
  assert.deepEqual(await loadVoiceActivityOnce("retry-test", async () => ["recovered"]), ["recovered"]);
});

test("같은 period의 서로 다른 기준 날짜 요청을 공유하지 않는다", async () => {
  const results = await Promise.all([
    loadVoiceActivityOnce("date-test", async () => ["august"], VoiceActivityPeriod.MONTH, "2026-08-01"),
    loadVoiceActivityOnce("date-test", async () => ["september"], VoiceActivityPeriod.MONTH, "2026-09-01"),
  ]);
  assert.deepEqual(results, [["august"], ["september"]]);
});
