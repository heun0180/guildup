import test from "node:test";
import assert from "node:assert/strict";
import {
  ACTIVITY_PERIOD_OPTIONS,
  MINIMUM_CLAN_MEMBER_OPTIONS,
  activityRulePayload,
} from "../src/activityRule.js";

test("14일과 본인 포함 2명을 숫자 설정으로 만든다", () => {
  assert.deepEqual(activityRulePayload("14", "2"), {
    activityPeriodDays: 14,
    minimumClanMembersInRoster: 2,
  });
});

test("화면에서 기간과 활동 인정 인원 선택지를 제한한다", () => {
  assert.deepEqual(ACTIVITY_PERIOD_OPTIONS, [7, 14, 30]);
  assert.deepEqual(MINIMUM_CLAN_MEMBER_OPTIONS, [2, 3, 4]);
});

test("지원하지 않는 기간과 인원을 거부한다", () => {
  assert.throws(() => activityRulePayload(0, 2), /활동 확인 기간/);
  assert.throws(() => activityRulePayload(366, 2), /활동 확인 기간/);
  assert.throws(() => activityRulePayload(14, 1), /활동 인정 인원/);
  assert.throws(() => activityRulePayload(14, 5), /활동 인정 인원/);
});
