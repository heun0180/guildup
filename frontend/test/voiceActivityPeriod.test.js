import test from "node:test";
import assert from "node:assert/strict";
import { addVoiceDays, isCurrentVoicePeriod, parseVoiceDate, readVoicePeriodSelection, seoulToday,
  shiftVoicePeriod, voiceActivityQuery, voiceCalendarDays, voicePeriodStart, voicePeriodTitle } from "../src/voiceActivityPeriod.js";

test("오늘은 브라우저 시간대와 무관하게 서울 날짜다", () => {
  assert.equal(seoulToday(new Date("2026-10-06T15:00:00Z")), "2026-10-07");
  assert.equal(seoulToday(new Date("2026-10-06T14:59:59Z")), "2026-10-06");
});

test("각 기간의 기준 날짜를 월요일·월초·연초로 정규화한다", () => {
  assert.equal(voicePeriodStart("DAY", "2026-10-07"), "2026-10-07");
  assert.equal(voicePeriodStart("WEEK", "2026-10-07"), "2026-10-05");
  assert.equal(voicePeriodStart("MONTH", "2026-10-07"), "2026-10-01");
  assert.equal(voicePeriodStart("YEAR", "2026-10-07"), "2026-01-01");
});

test("화살표 이동은 윤년·연말·월말을 달력 기준으로 처리한다", () => {
  assert.equal(shiftVoicePeriod("DAY", "2024-03-01", -1), "2024-02-29");
  assert.equal(shiftVoicePeriod("DAY", "2026-01-01", -1), "2025-12-31");
  assert.equal(shiftVoicePeriod("WEEK", "2026-01-01", -1), "2025-12-22");
  assert.equal(shiftVoicePeriod("MONTH", "2026-01-31", -1), "2025-12-01");
  assert.equal(shiftVoicePeriod("YEAR", "2026-10-07", -1), "2025-01-01");
  assert.equal(shiftVoicePeriod("DAY", "0001-01-01", -1), null);
});

test("날짜 범위는 월요일부터 일요일까지 표시한다", () => {
  assert.equal(voicePeriodTitle("WEEK", "2026-10-07"), "2026.10.05 ~ 2026.10.11");
  assert.equal(voicePeriodTitle("MONTH", "2026-08-01"), "2026년 8월");
  assert.equal(voicePeriodTitle("YEAR", "2025-01-01"), "2025년");
});

test("URL의 과거 기간을 유지하고 미래·잘못된 날짜는 현재 기간으로 보정한다", () => {
  assert.deepEqual(readVoicePeriodSelection(new URLSearchParams("period=MONTH&referenceDate=2026-08-20"), "2026-10-07"),
    { period: "MONTH", referenceDate: "2026-08-01" });
  for (const [period, date, current] of [["DAY", "2026-10-08", "2026-10-07"], ["WEEK", "2026-10-12", "2026-10-05"],
    ["MONTH", "2026-11-01", "2026-10-01"], ["YEAR", "2027-01-01", "2026-01-01"], ["DAY", "2026-02-30", "2026-10-07"]]) {
    assert.equal(readVoicePeriodSelection(new URLSearchParams({ period, referenceDate: date }), "2026-10-07").referenceDate, current);
  }
  assert.deepEqual(readVoicePeriodSelection(new URLSearchParams("period=INVALID"), "2026-10-07"), { period: "ALL", referenceDate: null });
  assert.deepEqual(readVoicePeriodSelection(new URLSearchParams(), "2026-10-07"), { period: "ALL", referenceDate: null });
});

test("API 파라미터는 하나의 referenceDate를 사용하고 ALL에는 날짜를 보내지 않는다", () => {
  assert.equal(voiceActivityQuery("MONTH", "2026-08-20"), "period=MONTH&referenceDate=2026-08-01");
  assert.equal(voiceActivityQuery("ALL", "2026-08-20"), "period=ALL");
  assert.equal(isCurrentVoicePeriod("MONTH", "2026-10-01", "2026-10-07"), true);
  assert.equal(isCurrentVoicePeriod("MONTH", "2026-09-01", "2026-10-07"), false);
});

test("달력은 월요일부터 시작하고 날짜 유효성은 윤년까지 검사한다", () => {
  const days = voiceCalendarDays("2026-10-07");
  assert.equal(days.length, 42);
  assert.equal(days[0], "2026-09-28");
  assert.equal(days[41], "2026-11-08");
  assert.equal(parseVoiceDate("2026-02-29"), null);
  assert.ok(parseVoiceDate("2024-02-29"));
  assert.ok(parseVoiceDate("0001-01-01"));
  assert.equal(addVoiceDays("2026-12-31", 1), "2027-01-01");
});
