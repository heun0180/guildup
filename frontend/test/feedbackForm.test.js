import test from "node:test";
import assert from "node:assert/strict";
import { feedbackPayload } from "../src/feedbackForm.js";

test("문의 입력값을 정리해 API payload를 만든다", () => {
  assert.deepEqual(feedbackPayload({ type: "BUG", title: "  제목  ", content: "  내용  " }), {
    type: "BUG", title: "제목", content: "내용",
  });
});

test("문의 유형과 제목 및 내용 제한을 검증한다", () => {
  const valid = { type: "FEATURE", title: "제목", content: "내용" };
  for (const form of [
    { ...valid, type: "UNKNOWN" },
    { ...valid, title: "   " },
    { ...valid, title: "x".repeat(101) },
    { ...valid, title: "제목\nBcc: attacker@example.com" },
    { ...valid, content: "   " },
    { ...valid, content: "x".repeat(3001) },
  ]) assert.throws(() => feedbackPayload(form));
});
