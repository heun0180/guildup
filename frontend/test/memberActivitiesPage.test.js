import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";

const temp = await mkdtemp(resolve("node_modules/.activity-page-"));
const output = resolve(temp, "page.mjs");
await build({ stdin: { contents: `
  import React from 'react';
  import Page from './src/pages/MemberActivitiesPage.jsx';
  import Boundary from './src/community/GameScopeBoundary.jsx';
  export default function TestPage() { return <Boundary><Page /></Boundary>; }
`, resolveDir: process.cwd(), loader: "jsx" }, outfile: output,
  bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external",
  plugins: [{ name: "activity-page-context", setup(builder) {
    builder.onLoad({ filter: /CommunityContext\.jsx$/ }, () => ({ contents:
      "export const useCommunity = () => ({ community: globalThis.__activityCommunity });", loader: "js" }));
    builder.onLoad({ filter: /DashboardLayout\.jsx$/ }, () => ({ contents:
      "export default function Layout({ children }) { return children; }", loader: "js" }));
  } }],
});
const { default: Page } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));
const endpoint = "/api/communities/9/games/11/activities";
const response = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
function activities(status, nickname = "previous-player") {
  const now = new Date().toISOString();
  return { sync: { status, lastSyncAttemptAt: status === "NEVER_SYNCED" ? null : now,
    lastSuccessfulSyncAt: status === "SUCCESS" ? now : null,
    nextSyncAvailableAt: status === "NEVER_SYNCED" ? null : new Date(Date.now() + 10800000).toISOString(),
    syncAvailable: status === "NEVER_SYNCED" }, rule: { activityPeriodDays: 14, minimumClanMembersInRoster: 2 },
    totalMembers: 1, activeMembers: status === "SUCCESS" ? 1 : 0, noClanActivityMembers: 0,
    noRecentMatchMembers: 0, accountVerificationRequiredMembers: status === "SUCCESS" ? 0 : 1,
    members: [{ memberId: 1, discordNickname: "클랜원", gameNickname: nickname,
      status: status === "SUCCESS" ? "ACTIVE" : "ACCOUNT_VERIFICATION_REQUIRED", lastClanActivityAt: now }] };
}

async function mount(handle) {
  const dom = new JSDOM("<div id='root'></div>", {
    url: "http://localhost/member-activities.html?communityId=9&communityGameId=11",
  });
  const timers = new Map();
  let nextTimer = 0;
  dom.window.setTimeout = (callback, delay) => { timers.set(++nextTimer, { callback, delay }); return nextTimer; };
  dom.window.clearTimeout = (id) => timers.delete(id);
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true,
    __activityCommunity: { id: 9, role: "OWNER", name: "클랜" } })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    if (url === "/api/auth/csrf") return response({ token: "token" });
    if (url.endsWith("/nickname-rule/status")) return response({ configured: true });
    return handle(url, options);
  };
  const root = createRoot(document.getElementById("root"));
  await act(async () => root.render(React.createElement(BrowserRouter, null, React.createElement(Page))));
  let unmounted = false;
  return { requests, timers,
    posts: () => requests.filter((request) => request.options.method === "POST"),
    async poll() {
      const entry = [...timers.entries()].find(([, timer]) => timer.delay === 3000);
      assert.ok(entry, "server SYNCING schedules a 3 second poll");
      timers.delete(entry[0]);
      // Do not await a pending transport here: tests can unmount while GET remains in flight.
      await act(async () => { entry[1].callback(); });
    },
    async unmount() { if (!unmounted) { await act(async () => root.unmount()); unmounted = true; } },
    async close() {
      await this.unmount();
      globalThis.fetch = previousFetch;
      for (const [key, descriptor] of saved) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
      }
      dom.window.close();
    },
  };
}
async function clickSync() {
  const button = document.querySelector(".activity-sync-panel button");
  assert.equal(button.disabled, false);
  await act(async () => button.click());
}

test("202 starts progress and SUCCESS polling replaces the visible result without reload", async () => {
  let gets = 0;
  const page = await mount((url, options) => {
    if (url === `${endpoint}/sync` && options.method === "POST") return response(activities("SYNCING"), 202);
    assert.equal(url, endpoint);
    return response(activities(++gets === 1 ? "NEVER_SYNCED" : "SUCCESS", gets === 1 ? "previous-player" : "new-player"));
  });
  try {
    assert.equal(gets, 1);
    assert.equal(page.posts().length, 0);
    await clickSync();
    assert.equal(page.posts().length, 1);
    assert.match(document.body.textContent, /인게임 활동을 조회하고 있습니다/);
    assert.equal(document.querySelector(".activity-sync-panel button").disabled, true);
    await page.poll();
    assert.match(document.body.textContent, /활동 조회가 완료되었습니다/);
    assert.match(document.querySelector("tbody").textContent, /new-player/);
    assert.equal([...page.timers.values()].some((timer) => timer.delay === 3000), false);
    assert.equal(page.requests.at(-1).options.cache, "no-store");
  } finally { await page.close(); }
});

test("reopening a SYNCING page restores progress from GET without starting another worker", async () => {
  let gets = 0;
  const page = await mount(() => response(activities(++gets === 1 ? "SYNCING" : "SUCCESS", "resumed-player")));
  try {
    assert.match(document.body.textContent, /인게임 활동을 조회하고 있습니다/);
    await page.poll();
    assert.match(document.body.textContent, /활동 조회가 완료되었습니다/);
    assert.match(document.querySelector("tbody").textContent, /resumed-player/);
    assert.equal(page.posts().length, 0);
  } finally { await page.close(); }
});

test("worker FAILED is shown after polling while the previous activity result remains", async () => {
  let gets = 0;
  const page = await mount(() => response(activities(++gets === 1 ? "SYNCING" : "FAILED")));
  try {
    await page.poll();
    assert.match(document.querySelector('[role="alert"]').textContent, /활동 조회에 실패했습니다/);
    assert.match(document.querySelector("tbody").textContent, /previous-player/);
    assert.equal([...page.timers.values()].some((timer) => timer.delay === 3000), false);
  } finally { await page.close(); }
});

test("leaving cancels only polling and ignores a late GET completion", async () => {
  let gets = 0, resolvePending, pendingSignal;
  const page = await mount((url, options) => {
    if (++gets === 1) return response(activities("SYNCING"));
    pendingSignal = options.signal;
    return new Promise((done) => { resolvePending = done; });
  });
  try {
    await page.poll();
    assert.equal(pendingSignal.aborted, false);
    await page.unmount();
    assert.equal(pendingSignal.aborted, true);
    await act(async () => resolvePending(response(activities("SUCCESS"))));
    assert.equal(page.posts().length, 0);
    assert.equal(page.timers.size, 0);
  } finally { await page.close(); }
});

test("409 restores the server SYNCING state and then follows its successful completion", async () => {
  let gets = 0;
  const page = await mount((url) => {
    if (url.endsWith("/sync")) return response({ message: "이미 조회 중" }, 409);
    return response(activities(["NEVER_SYNCED", "SYNCING", "SUCCESS"][gets++]));
  });
  try {
    await clickSync();
    assert.match(document.body.textContent, /이미 활동 정보를 조회 중/);
    await page.poll();
    assert.match(document.body.textContent, /활동 조회가 완료되었습니다/);
    assert.equal(page.posts().length, 1);
  } finally { await page.close(); }
});

test("429 refreshes the existing cooldown and never starts polling", async () => {
  let gets = 0;
  const page = await mount((url) => url.endsWith("/sync")
    ? response({ message: "대기시간" }, 429)
    : response(activities(++gets === 1 ? "NEVER_SYNCED" : "SUCCESS")));
  try {
    await clickSync();
    assert.match(document.body.textContent, /아직 활동 정보를 다시 조회할 수 없습니다/);
    assert.equal(document.querySelector(".activity-sync-panel button").disabled, true);
    assert.equal([...page.timers.values()].some((timer) => timer.delay === 3000), false);
  } finally { await page.close(); }
});
