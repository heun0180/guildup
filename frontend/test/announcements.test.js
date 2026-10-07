import test from "node:test";
import assert from "node:assert/strict";
import { announcementPayload, announcementUrl, ANNOUNCEMENT_TYPES } from "../src/announcements.js";

const form = { title: "서비스 공지", content: "본문", type: "NOTICE", important: false, pinned: false,
  popup: false, published: false, publishStartAt: "", publishEndAt: "" };

test("all five types and public URLs are independent of community news", () => {
  assert.deepEqual(Object.values(ANNOUNCEMENT_TYPES), ["공지", "업데이트", "점검", "장애", "이벤트"]);
  assert.equal(announcementUrl(), "/announcements");
  assert.equal(announcementUrl(7), "/announcements?id=7");
  assert.doesNotMatch(announcementUrl(7), /communityId/);
});

test("validates required lengths, type, popup importance and exclusive end boundaries", () => {
  for (const change of [{ title: " " }, { content: " " }, { title: "x".repeat(201) },
    { content: "x".repeat(20001) }, { type: "UNKNOWN" }, { popup: true },
    { publishStartAt: "invalid" }, { publishStartAt: "2026-10-10T09:00", publishEndAt: "2026-10-10T09:00" },
    { publishStartAt: "2026-10-10T09:00", publishEndAt: "2026-10-09T09:00" }]) {
    assert.throws(() => announcementPayload({ ...form, ...change }));
  }
  assert.doesNotThrow(() => announcementPayload({ ...form, important: true, popup: true }));
});

test("converts local publication times to UTC and preserves optional unbounded dates", () => {
  const payload = announcementPayload({ ...form, title: " 서비스 공지 ", publishStartAt: "2026-10-10T09:00:30", publishEndAt: "2026-10-20T23:59:00" });
  assert.equal(payload.title, form.title);
  assert.equal(payload.publishStartAt, new Date("2026-10-10T09:00:30").toISOString());
  assert.equal(payload.publishEndAt, new Date("2026-10-20T23:59:00").toISOString());
  assert.equal(announcementPayload(form).publishStartAt, null);
  assert.equal(announcementPayload(form).publishEndAt, null);
  assert.equal(announcementPayload({ ...form, createdBy: 99, targetType: "GAME", userId: 99 }).targetType, undefined);
});
