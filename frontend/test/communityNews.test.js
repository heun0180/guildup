import test from "node:test";
import assert from "node:assert/strict";
import { canManageNews, newsPayload, newsUrl, toLocalDateTime, newsError } from "../src/communityNews.js";

const notice = { title: "공지", content: "내용", pinned: true, important: false };
const event = { title: "내전", content: "", type: "CLAN_MATCH", startAt: "2026-09-28T20:00:00", endAt: "2026-09-28T23:00:00" };

test("only OWNER and ADMIN can manage news, without a Discord connection", () => {
  for (const role of ["OWNER", "ADMIN"]) assert.equal(canManageNews({ role, discordConnected: false }), true);
  for (const role of ["MEMBER", "unknown", null]) assert.equal(canManageNews({ role }), false);
  assert.equal(canManageNews(null), false);
});

test("validates required and maximum notice title/content lengths", () => {
  assert.deepEqual(newsPayload({ ...notice, title: " 공지 " }, true), notice);
  for (const invalid of [{ title: " " }, { content: " " }, { title: "x".repeat(201) }, { content: "x".repeat(20001) }]) {
    assert.throws(() => newsPayload({ ...notice, ...invalid }, true));
  }
});

test("validates event dates and accepts equal start/end and optional description", () => {
  const payload = newsPayload(event, false);
  assert.equal(payload.startAt, new Date(event.startAt).toISOString());
  assert.equal(payload.endAt, new Date(event.endAt).toISOString());
  assert.equal(payload.content, "");
  assert.doesNotThrow(() => newsPayload({ ...event, endAt: event.startAt }, false));
  for (const invalid of [{ startAt: "" }, { endAt: "" }, { startAt: "invalid" }, { endAt: "2026-09-27T20:00" }, { type: "INVALID" }]) {
    assert.throws(() => newsPayload({ ...event, ...invalid }, false));
  }
});

test("editing preserves local date/time across UTC conversion including seconds", () => {
  for (const date of ["2026-09-28T11:00:30Z", "2026-01-01T00:00:00Z"]) {
    assert.equal(new Date(toLocalDateTime(date)).toISOString(), new Date(date).toISOString());
  }
  assert.equal(toLocalDateTime(null), "");
});

test("detail links keep community and selected tab and errors do not leak server internals", () => {
  assert.equal(newsUrl(12, "events", 7), "/community-news.html?communityId=12&tab=events&id=7");
  assert.equal(newsUrl(12), "/community-news.html?communityId=12&tab=notices");
  assert.ok(!newsError({ status: 500, message: "SQL stacktrace" }).includes("SQL"));
});
