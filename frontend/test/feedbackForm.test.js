import test from "node:test";
import assert from "node:assert/strict";
import { feedbackPayload, FEEDBACK_TYPES, supportContext, safeSupportRoute } from "../src/feedbackForm.js";

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

test("서비스 문의를 포함한 네 유형을 지원하고 context에서 쿼리와 해시를 제외한다", () => {
  assert.equal(Object.keys(FEEDBACK_TYPES).length, 4);
  assert.deepEqual(feedbackPayload({ type: "SERVICE", title: "제목", content: "내용" }), {
    type: "SERVICE", title: "제목", content: "내용",
  });
  assert.deepEqual(supportContext({ pathname: "/communities.html", search: "?access_token=secret", hash: "#secret" }), {
    pageRoute: "/communities.html", communityId: null,
  });
  assert.deepEqual(supportContext({ pathname: "/members.html", search: "?communityId=12&token=secret" }), {
    pageRoute: "/members.html", communityId: 12,
  });
  assert.equal(safeSupportRoute("https://user:password@example.com/account.html?token=secret#secret"), "/account.html");
  assert.equal(safeSupportRoute("/path/secret-token-value"), null);
  assert.equal(supportContext({ pathname: "/support", search: "?communityId=9007199254740992" }).communityId, null);
});
