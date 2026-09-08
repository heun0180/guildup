import test from "node:test";
import assert from "node:assert/strict";
import { loadActivityPageData } from "../src/activityPageLoader.js";

test("인게임 닉네임 규칙이 없으면 활동 API를 호출하지 않는다", async () => {
  let activityCalls = 0;
  const result = await loadActivityPageData({
    loadCommunity: async () => ({ id: 1, name: "치즈클랜" }),
    loadNicknameStatus: async () => ({ configured: false }),
    loadActivities: async () => {
      activityCalls += 1;
      return { members: [] };
    },
  });

  assert.equal(result.status, "notConfigured");
  assert.equal(result.activities, null);
  assert.equal(activityCalls, 0);
});

test("인게임 닉네임 규칙이 있으면 기존 활동 API 결과를 반환한다", async () => {
  const activities = { members: [{ memberId: 1 }] };
  const result = await loadActivityPageData({
    loadCommunity: async () => ({ id: 1 }),
    loadNicknameStatus: async () => ({ configured: true }),
    loadActivities: async () => activities,
  });

  assert.equal(result.status, "configured");
  assert.equal(result.activities, activities);
});

test("설정 완료 뒤 활동 API가 실패하면 실패 단계를 구분한다", async () => {
  await assert.rejects(
    loadActivityPageData({
      loadCommunity: async () => ({ id: 1 }),
      loadNicknameStatus: async () => ({ configured: true }),
      loadActivities: async () => { throw new Error("활동 조회 실패"); },
    }),
    (error) => error.activityLoadStage === "activities",
  );
});
