import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";

const temp = await mkdtemp(resolve("node_modules/.developer-users-"));
const output = resolve(temp, "pages.mjs");
await build({ stdin: { contents: `
  export { DeveloperUsersPage as Page, DeveloperUserDetailPage as Detail } from "./src/developer/DeveloperUsersPage.jsx";
  export { DeveloperDashboardPage as Dashboard } from "./src/developer/DeveloperPages.jsx";
  export { default as Layout } from "./src/developer/DeveloperLayout.jsx";
`, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external" });
const { Page, Detail, Dashboard, Layout } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));

const stats = { totalUsers: 101, newUsersToday: 3, newUsersLast7Days: 7, activeUsersLast30Days: 30, normalUsers: 100 };
const user = { id: 42, nickname: "연결 회원", loginMethod: "EMAIL_DISCORD", email: "linked@example.com", discordUserId: "discord-42",
  discordUsername: "discord-name", communityCount: 2, lastLoginAt: null, lastActiveAt: null, createdAt: "2026-10-10T00:00:00Z", status: "ACTIVE" };
const page = { content: [user], page: 0, size: 20, totalElements: 101, totalPages: 6 };
const response = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function mount(Component, handle, { url = "/developer/users", layout = false, allowed = true } = {}) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${url}` });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, FormData: dom.window.FormData, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  dom.window.HTMLElement.prototype.attachEvent = function () {};
  dom.window.HTMLElement.prototype.detachEvent = function () {};
  const previousFetch = globalThis.fetch, requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    if (url === "/api/auth/me") return response({ id: 1, nickname: "관리자", systemAdmin: allowed });
    if (url === "/api/developer/users/statistics") return response(stats);
    return handle(url, options);
  };
  const root = createRoot(document.getElementById("root"));
  const route = React.createElement(Route, { path: "/developer/users/:userId", element: React.createElement(Detail) });
  const current = React.createElement(Route, { path: "/developer/users", element: React.createElement(Component) });
  const dashboard = React.createElement(Route, { path: "/developer", element: React.createElement(Dashboard) });
  const routes = layout ? React.createElement(Route, { element: React.createElement(Layout) }, current, route, dashboard) : [current, route, dashboard];
  await act(async () => root.render(React.createElement(BrowserRouter, null, React.createElement(Routes, null, routes))));
  return { requests, async navigate(path) {
    await act(async () => { window.history.pushState({}, "", path); window.dispatchEvent(new window.PopStateEvent("popstate")); });
  }, async close() {
    await act(async () => root.unmount()); globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  } };
}

test("list renders all statistics, linked login badge, no-record dates and server pagination", async () => {
  const view = await mount(Page, (url) => response({ ...page, page: Number(new URL(url, "http://localhost").searchParams.get("page")) }));
  try {
    assert.equal(document.querySelectorAll(".developer-user-stats article").length, 5);
    assert.match(document.querySelector("tbody").textContent, /이메일 \+ Discord 연결/);
    assert.match(document.querySelector("tbody").textContent, /기록 없음/);
    assert.equal(document.querySelector('a[href="/developer/users/42"]').textContent, "42");
    assert.equal(document.querySelector(".developer-pager button").disabled, true);
    await act(async () => [...document.querySelectorAll(".developer-pager button")].at(-1).click());
    const request = new URL(view.requests.at(-1).url, "http://localhost");
    assert.equal(request.searchParams.get("page"), "1"); assert.equal(request.searchParams.get("size"), "20");
    assert.equal(view.requests.at(-1).options.cache, "no-store");
  } finally { await view.close(); }
});

test("search/filter/sort URLs retain criteria, reset the page and follow browser navigation", async () => {
  const view = await mount(Page, () => response({ ...page, page: 2 }), { url: "/developer/users?q=linked%40example.com&page=2" });
  try {
    const form = document.querySelector(".developer-user-filters");
    assert.equal(form.querySelector("input").value, "linked@example.com");
    const select = form.querySelector("select");
    await act(async () => { select.value = "EMAIL_DISCORD"; select.dispatchEvent(new Event("change", { bubbles: true })); });
    const req = new URL(view.requests.at(-1).url, "http://localhost");
    assert.equal(req.searchParams.get("q"), "linked@example.com"); assert.equal(req.searchParams.get("loginMethod"), "EMAIL_DISCORD");
    assert.equal(req.searchParams.get("page"), "0");
    await view.navigate("/developer/users?q=42&sort=lastLoginAt&direction=asc");
    assert.equal(form.querySelector("input").value, "42");
    assert.equal(new URL(view.requests.at(-1).url, "http://localhost").searchParams.get("sort"), "lastLoginAt");
    await act(async () => form.querySelector('button[type="submit"]').click());
    assert.equal(new URL(view.requests.at(-1).url, "http://localhost").searchParams.get("q"), "42");
  } finally { await view.close(); }
});

test("detail renders two accounts, community-specific roles and links, including ended memberships", async () => {
  const detail = { user, loginAccounts: [{ provider: "EMAIL", accountId: user.email, externalUserId: null, linkedAt: user.createdAt },
    { provider: "DISCORD", accountId: "discord-name", externalUserId: "discord-42", linkedAt: null }],
    communities: [{ communityId: 5, communityName: "소유 커뮤니티", nickname: "클랜 닉네임", role: "OWNER", joinedAt: null, status: "ACTIVE", memberStatus: "ACTIVE" },
      { communityId: 7, communityName: "종료된 커뮤니티", nickname: null, role: "MEMBER", joinedAt: user.createdAt, status: "ENDED", endedAt: user.createdAt, memberStatus: null }] };
  const view = await mount(Detail, () => response(detail), { url: "/developer/users/42" });
  try {
    assert.equal(document.querySelectorAll(".developer-account-table tbody tr").length, 2);
    assert.ok(document.querySelector('a[href="/developer/communities/5"]'));
    assert.match(document.body.textContent, /OWNER/); assert.match(document.body.textContent, /MEMBER/);
    assert.match(document.body.textContent, /클랜 닉네임/); assert.match(document.body.textContent, /종료/);
    assert.match(document.body.textContent, /기록 없음/);
  } finally { await view.close(); }
});

test("loading, empty, failure/retry and out-of-range pages recover without rendering stale users", async () => {
  let release, fail = false;
  const view = await mount(Page, () => new Promise((resolve) => { release = resolve; }));
  try {
    assert.match(document.body.textContent, /불러오는 중/);
    await act(async () => release(response({ ...page, content: [], totalElements: 0, totalPages: 0 })));
    assert.match(document.body.textContent, /조회된 회원이 없습니다/);
    globalThis.fetch = async (url) => url.endsWith("/statistics") ? response(stats) : fail ? response(page) : response({ message: "조회 실패" }, 503);
    await view.navigate("/developer/users?q=42");
    assert.ok(document.querySelector('[role="alert"]'));
    fail = true;
    await act(async () => document.querySelector(".developer-user-error button").click());
    assert.match(document.querySelector("tbody").textContent, /연결 회원/);
  } finally { await view.close(); }
});

test("switching user detail hides the old response while the new request is pending", async () => {
  let release;
  const view = await mount(Detail, (url) => url.endsWith("/42") ? response({ user, loginAccounts: [], communities: [] })
    : new Promise((resolve) => { release = resolve; }), { url: "/developer/users/42" });
  try {
    await view.navigate("/developer/users/99");
    assert.doesNotMatch(document.body.textContent, /연결 회원/);
    await act(async () => release(response({ user: { ...user, id: 99, nickname: "다음 회원" }, loginAccounts: [], communities: [] })));
    assert.match(document.body.textContent, /다음 회원/);
  } finally { await view.close(); }
});

test("dashboard displays login identities and a link to all users", async () => {
  const view = await mount(Dashboard, () => response({ communityCount: 0, userCount: 1, clanMemberCount: 0, discordConnectedCommunityCount: 0,
    activeBingoCount: 0, activeKillCompetitionCount: 0, recentCommunities: [], recentUsers: [user] }), { url: "/developer" });
  try {
    assert.equal(document.querySelector('.developer-recent-users a[href="/developer/users"]').textContent, "전체 회원 보기");
    assert.match(document.querySelector(".developer-recent-users").textContent, /linked@example.com/);
    assert.match(document.querySelector(".developer-recent-users").textContent, /discord-42/);
    assert.match(document.querySelector(".developer-recent-users").textContent, /정상/);
  } finally { await view.close(); }
});

test("layout exposes the active user-management menu only to system admins", async () => {
  const view = await mount(Page, () => response(page), { layout: true });
  try { assert.ok(document.querySelector('.sidebar a.is-active[href="/developer/users"]')); }
  finally { await view.close(); }
  const denied = await mount(Page, () => { throw new Error("member endpoint must not be requested"); }, { layout: true, allowed: false });
  try { assert.match(document.body.textContent, /접근 권한이 없습니다/); assert.equal(document.querySelector(".sidebar"), null); }
  finally { await denied.close(); }
});
