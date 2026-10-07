import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { duration, isSlow, monitoringQuery } from "../src/developer/monitoringView.js";

const temp = await mkdtemp(resolve("node_modules/.monitoring-page-"));
const output = resolve(temp, "page.mjs");
await build({ entryPoints: ["src/developer/MonitoringPage.jsx"], outfile: output, bundle: true, platform: "node",
  format: "esm", jsx: "automatic", packages: "external", plugins: [{ name: "live-logs-isolation", setup(builder) {
    builder.onLoad({ filter: /LiveLogsPanel\.jsx$/ }, () => ({ contents: "export default function Panel() { return null; }", loader: "js" }));
  } }],
});
const { default: Page } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));
const syncId = "12345678-1234-1234-1234-123456789abc";
const base = { communityId: 12, communityName: "치즈", userId: 4, referenceId: syncId,
  category: "SYSTEM", occurredAt: "2026-10-07T04:30:00Z", metadata: { syncId, communityGameId: 31,
    gameType: "BATTLEGROUNDS_KAKAO", startedAt: "2026-10-07T04:30:00Z", memberCount: 96, targetAccounts: 96 } };
const started = { ...base, id: 1, severity: "INFO", eventCode: "ACTIVITY_SYNC_STARTED", message: "조회 시작" };
const completed = { ...base, id: 3, severity: "INFO", eventCode: "ACTIVITY_SYNC_COMPLETED", message: "완료",
  metadata: { ...base.metadata, syncStatus: "SUCCESS", durationMs: 168500, playersFound: 96, uniqueMatches: 3235, snapshotCount: 78 } };
const failed = { ...base, id: 4, severity: "ERROR", eventCode: "ACTIVITY_SYNC_FAILED", message: "인게임 활동 조회 실패",
  metadata: { ...base.metadata, syncStatus: "FAILED", failureStage: "MATCH_FETCH", stage: "MATCH_FETCH", durationMs: 72100,
    errorType: "PUBG_API", pubgErrorCode: "PUBG_TIMEOUT", errorMessage: "PUBG API timeout", exceptionClass: "PubgApiException" } };
const rate = { ...base, id: 2, severity: "WARN", category: "PUBG_API", eventCode: "PUBG_API_RATE_LIMIT", message: "PUBG_RATE_LIMIT" };
const summary = { server: { status: "UP", uptimeSeconds: 60 }, database: { status: "UP" }, last24Hours: {} };
const response = (body) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const pageOf = (content) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

async function mount(handle) {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/developer/monitoring" });
  dom.window.setInterval = () => 1; dom.window.clearInterval = () => {};
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(String(url));
    if (url === "/api/developer/monitoring/summary") return response(summary);
    return response(handle(new URL(url, "http://localhost")));
  };
  const root = createRoot(document.getElementById("root"));
  await act(async () => root.render(React.createElement(Page)));
  return { requests, async close() {
    await act(async () => root.unmount()); globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  } };
}

function changeSelect(selector, value) {
  const select = document.querySelector(selector); select.value = value;
  select.dispatchEvent(new window.Event("change", { bubbles: true }));
}

test("successful syncs display duration and SLOW and submit activity filters", async () => {
  const view = await mount((url) => pageOf([completed]));
  try {
    assert.match(document.querySelector("tbody").textContent, /SUCCESS/);
    assert.match(document.querySelector("tbody").textContent, /168\.5s/);
    assert.match(document.querySelector("tbody").textContent, /SLOW/);
    assert.match(document.querySelector("tbody").textContent, /BATTLEGROUNDS_KAKAO/);
    await act(async () => [...document.querySelectorAll('.monitoring-log-groups button')].find((button) => button.textContent === "인게임 활동").click());
    let query = new URL(view.requests.at(-1), "http://localhost").searchParams;
    assert.equal(query.get("group"), "ACTIVITY"); assert.ok(query.get("from"));
    const selects = [...document.querySelectorAll('.monitoring-filters label')];
    const selector = (label) => `.monitoring-filters label:nth-child(${selects.findIndex((item) => item.querySelector("span").textContent === label) + 1}) select`;
    await act(async () => changeSelect(selector("Game"), "BATTLEGROUNDS_KAKAO"));
    await act(async () => changeSelect(selector("최종 상태"), "SUCCESS"));
    await act(async () => changeSelect(selector("총 처리시간"), "60000"));
    await act(async () => document.querySelector("form.monitoring-filters").dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true })));
    query = new URL(view.requests.at(-1), "http://localhost").searchParams;
    assert.equal(query.get("gameType"), "BATTLEGROUNDS_KAKAO");
    assert.equal(query.get("syncStatus"), "SUCCESS"); assert.equal(query.get("minDurationMs"), "60000");
  } finally { await view.close(); }
});

test("failure detail shows the cause and correlated timeline and keeps stack trace collapsed", async () => {
  const view = await mount((url) => {
    if (url.pathname.endsWith("/4")) return { ...failed, metadata: { ...failed.metadata, stackTrace: "PubgApiException: [REDACTED]\n at Worker.sync" } };
    if (url.searchParams.get("syncId")) {
      assert.equal(url.searchParams.get("syncId"), syncId);
      assert.equal(url.searchParams.get("group"), url.searchParams.get("order") === "ASC" ? null : "ACTIVITY");
      return pageOf(url.searchParams.get("order") === "ASC" ? [started, rate, failed] : [failed]);
    }
    return pageOf([failed]);
  });
  try {
    assert.doesNotMatch(document.querySelector("tbody").textContent, /Worker\.sync/);
    await act(async () => document.querySelector("tbody tr").click());
    const modal = document.querySelector('[role="dialog"]');
    assert.ok(modal); assert.match(modal.textContent, /MATCH_FETCH/); assert.match(modal.textContent, /PUBG API timeout/);
    assert.match(modal.textContent, /72\.1s/); assert.match(modal.textContent, /치즈/);
    const rows = [...modal.querySelectorAll(".monitoring-timeline li")];
    assert.equal(rows.length, 3); assert.match(rows[1].textContent, /PUBG_API_RATE_LIMIT/);
    const trace = modal.querySelector("details"); assert.equal(trace.open, false); assert.match(trace.textContent, /\[REDACTED\]/);
    await act(async () => window.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape" })));
    assert.equal(document.querySelector('[role="dialog"]'), null);
  } finally { await view.close(); }
});

test("duration thresholds and empty filters preserve zero values and exact syncId search", () => {
  assert.equal(duration(0), "0.0s"); assert.equal(duration(undefined), "-");
  assert.equal(isSlow({ metadata: { durationMs: 59999 } }), false);
  assert.equal(isSlow({ metadata: { durationMs: 60000 } }), true);
  const query = new URLSearchParams(monitoringQuery({ syncId: ` ${syncId} `, minDurationMs: "0", severity: "", from: "2026-10-07" }, 2));
  assert.equal(query.get("syncId"), syncId); assert.equal(query.get("minDurationMs"), "0"); assert.equal(query.get("page"), "2");
});
