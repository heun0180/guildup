import test from "node:test";
import assert from "node:assert/strict";
import { loadGameNicknameStatus } from "../src/gameNicknameStatus.js";

test("상태 API가 없으면 기존 닉네임 규칙 API로 설정 여부를 확인한다", async () => {
  let ruleCalls = 0;
  const status = await loadGameNicknameStatus({
    loadStatus: async () => { throw Object.assign(new Error("Not Found"), { status: 404 }); },
    loadRule: async () => {
      ruleCalls += 1;
      return { configured: true };
    },
  });

  assert.equal(status.configured, true);
  assert.equal(ruleCalls, 1);
});

test("상태 API의 404 외 오류는 미설정으로 바꾸지 않는다", async () => {
  const failure = Object.assign(new Error("Server Error"), { status: 500 });

  await assert.rejects(
    loadGameNicknameStatus({ loadStatus: async () => { throw failure; }, loadRule: async () => ({ configured: true }) }),
    failure,
  );
});
