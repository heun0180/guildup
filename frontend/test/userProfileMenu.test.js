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

const temp = await mkdtemp(resolve("node_modules/.profile-menu-"));
const output = resolve(temp, "header.mjs");
await build({ stdin: { contents: `
  export { default as Header } from "./src/components/AppHeader.jsx";
  export { HelpIndexPage, BingoHelpPage } from "./src/pages/HelpPages.jsx";
  export { AnnouncementProvider } from "./src/announcement/AnnouncementContext.jsx";
  `, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm",
  jsx: "automatic", packages: "external" });
const { Header, HelpIndexPage, BingoHelpPage, AnnouncementProvider } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));

const currentUser = { id: 9, nickname: "애플", systemAdmin: false };
const announcement = { id: 7, title: "서비스 업데이트", type: "UPDATE", read: false, newAnnouncement: true,
  createdAt: "2026-10-07T00:00:00Z" };
const response = (body, status = 200) => status === 204 ? new Response(null, { status })
  : new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function mount({ Component = Header, props = { actions: true }, user = currentUser,
  handle = () => null, strict = false, path = "/communities.html" } = {}) {
  const dom = new JSDOM("<div id='root'></div><button id='outside'>외부 버튼</button>", { url: `http://localhost${path}` });
  const redirects = [];
  const testLocation = new Proxy({}, { get(_target, key) {
    return key === "replace" ? (url) => redirects.push(url) : Reflect.get(dom.window.location, key, dom.window.location);
  } });
  const testWindow = new Proxy(dom.window, { get(target, key) {
    if (key === "location") return testLocation;
    const value = Reflect.get(target, key, target);
    return ["addEventListener", "removeEventListener", "setInterval", "clearInterval"].includes(key)
      ? value.bind(target) : value;
  } });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: testWindow, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const requests = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    const overridden = await handle(url, options);
    if (overridden) return overridden;
    if (url === "/api/auth/me") return user ? response(user) : response({ message: "Login required" }, 401);
    if (url === "/api/auth/csrf") return response({ token: "existing-session-token" });
    if (url === "/api/auth/logout") return response(null, 204);
    if (url === "/api/announcements/notifications") return response({ unreadCount: 3, recent: [announcement] });
    if (url === "/api/announcements/popup") return response(null, 204);
    throw new Error(`Unexpected request: ${url}`);
  };
  const root = createRoot(document.getElementById("root"));
  const content = React.createElement(BrowserRouter, { window: dom.window },
    React.createElement(AnnouncementProvider, null, React.createElement(Component, props)));
  async function close() {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  }
  try { await act(async () => root.render(strict ? React.createElement(React.StrictMode, null, content) : content)); }
  catch (error) { await close(); throw error; }
  return { requests, redirects, close };
}

const profile = () => document.querySelector(".profile-menu-trigger");
const menu = () => document.querySelector('[role="menu"]');
const items = () => [...document.querySelectorAll('[role="menuitem"]')];
async function click(element) {
  assert.ok(element);
  await act(async () => {
    element.dispatchEvent(new window.MouseEvent("pointerdown", { bubbles: true }));
    element.focus(); element.click();
  });
}
async function key(element, value) {
  await act(async () => element.dispatchEvent(new window.KeyboardEvent("keydown", { key: value, bubbles: true, cancelable: true })));
}

test("entire profile area toggles a compact menu and the header retains its bell", async () => {
  const view = await mount({ strict: true });
  try {
    assert.equal(document.querySelectorAll(".header-actions > *").length, 2);
    assert.ok(document.querySelector(".announcement-bell-button"));
    assert.ok(profile().querySelector(".header-avatar"));
    assert.equal(profile().querySelector(".profile-menu-name").textContent, "애플");
    assert.equal(profile().getAttribute("aria-haspopup"), "menu");
    await click(profile()); assert.ok(menu());
    assert.deepEqual(items().map((item) => item.textContent), ["내 커뮤니티", "계정", "문의 / 건의", "공지NEW", "가이드", "로그아웃"]);
    assert.ok(menu().querySelector('[role="separator"]'));
    assert.equal(document.activeElement, items()[0]);
    await click(profile()); assert.equal(menu(), null);
  } finally { await view.close(); }
});

test("outside click, Escape and focus leaving close the menu; Escape restores trigger focus", async () => {
  const view = await mount();
  try {
    await click(profile()); await click(document.getElementById("outside")); assert.equal(menu(), null);
    await click(profile()); await key(document.activeElement, "Escape");
    assert.equal(menu(), null); assert.equal(document.activeElement, profile());
    await click(profile()); await act(async () => document.getElementById("outside").focus());
    assert.equal(menu(), null);
  } finally { await view.close(); }
});

test("keyboard users can open, navigate, wrap and leave with Tab", async () => {
  const view = await mount();
  try {
    await key(profile(), "ArrowDown"); assert.equal(document.activeElement, items()[0]);
    await key(document.activeElement, "ArrowDown"); assert.equal(document.activeElement, items()[1]);
    await key(document.activeElement, "End"); assert.equal(document.activeElement, items().at(-1));
    await key(document.activeElement, "ArrowDown"); assert.equal(document.activeElement, items()[0]);
    await key(document.activeElement, "ArrowUp"); assert.equal(document.activeElement, items().at(-1));
    await key(document.activeElement, "Home"); assert.equal(document.activeElement, items()[0]);
    await key(document.activeElement, "Tab"); assert.equal(menu(), null);
    await key(profile(), "ArrowUp"); assert.equal(document.activeElement, items().at(-1));
  } finally { await view.close(); }
});

test("community, announcement and guide links use existing routes and close after navigation", async () => {
  const view = await mount();
  try {
    for (const href of ["/communities.html", "/announcements", "/help"]) {
      await click(profile());
      await click(items().find((item) => item.getAttribute("href") === href));
      assert.equal(window.location.pathname, href); assert.equal(menu(), null);
    }
    assert.equal(view.requests.some(({ url }) => url.includes("/settings")), false);
  } finally { await view.close(); }
});

test("admin-only developer navigation is preserved inside the profile menu", async () => {
  const view = await mount({ user: { ...currentUser, systemAdmin: true }, props: { actions: true, communityId: "12" } });
  try {
    assert.equal(document.querySelector(".brand").getAttribute("href"), "/community-dashboard.html?communityId=12");
    await click(profile());
    assert.deepEqual(items().map((item) => item.textContent), ["내 커뮤니티", "계정", "문의 / 건의", "공지NEW", "가이드", "관리자", "로그아웃"]);
    assert.equal(items().some((item) => item.textContent === "설정"), false);
    await click(items().find((item) => item.getAttribute("href") === "/developer"));
    assert.equal(window.location.pathname, "/developer"); assert.equal(menu(), null);
  } finally { await view.close(); }
});

test("bell dropdown and profile menu remain separate and profile opening makes no extra API call", async () => {
  const view = await mount();
  try {
    const before = view.requests.length;
    await click(profile()); assert.equal(view.requests.length, before);
    await click(document.querySelector(".announcement-bell-button"));
    assert.equal(menu(), null); assert.ok(document.querySelector(".announcement-dropdown"));
    assert.match(document.querySelector(".announcement-dropdown").textContent, /서비스 업데이트/);
    assert.equal(document.querySelector(".announcement-all").getAttribute("href"), "/announcements");
    await click(profile()); assert.ok(menu()); assert.equal(document.querySelector(".announcement-dropdown"), null);
    assert.match(document.querySelector(".announcement-bell-button").getAttribute("aria-label"), /3개/);
  } finally { await view.close(); }
});

test("profile NEW badge follows existing server data instead of an invented client timer", async () => {
  const view = await mount({ handle: (url) => url === "/api/announcements/notifications"
    ? response({ unreadCount: 1, recent: [{ ...announcement, newAnnouncement: false }] }) : null });
  try { await click(profile()); assert.equal(document.querySelector(".profile-menu-new"), null); }
  finally { await view.close(); }
});

test("logout calls the existing CSRF-protected API and redirects to the existing login page", async () => {
  const view = await mount();
  try {
    await click(profile()); await click(items().at(-1));
    const request = view.requests.find(({ url }) => url === "/api/auth/logout");
    assert.equal(request.options.method, "POST");
    assert.equal(new Headers(request.options.headers).get("X-CSRF-Token"), "existing-session-token");
    assert.deepEqual(view.redirects, ["/login.html"]); assert.equal(menu(), null);
    assert.equal(view.requests.filter(({ url }) => url === "/api/auth/logout").length, 1);
  } finally { await view.close(); }
});

test("logout failure retains the existing header error and allows another attempt", async () => {
  const errors = [];
  const view = await mount({ props: { actions: true, onError: (message) => errors.push(message) },
    handle: (url) => url === "/api/auth/logout" ? response({ message: "로그아웃 재시도" }, 400) : null });
  try {
    await click(profile()); await click(items().at(-1));
    assert.match(document.querySelector('[role="alert"]').textContent, /로그아웃 재시도/);
    assert.deepEqual(errors, ["로그아웃 재시도"]); assert.deepEqual(view.redirects, []);
    await click(profile()); assert.ok(menu());
  } finally { await view.close(); }
});

test("public header and unauthorized sessions never expose user, logout or bell controls", async () => {
  const publicView = await mount({ props: { actions: false } });
  try {
    assert.equal(profile(), null); assert.equal(document.querySelector(".announcement-bell-button"), null);
    assert.equal(publicView.requests.length, 0);
    assert.equal(document.querySelector(".header-help-link").getAttribute("aria-label"), "가이드");
  } finally { await publicView.close(); }
  const unauthorized = await mount({ user: null });
  try {
    assert.equal(profile(), null); assert.equal(document.querySelector('[role="menuitem"]'), null);
    assert.equal(document.querySelector(".announcement-bell-button"), null);
    assert.deepEqual(unauthorized.redirects, ["/login.html"]);
  } finally { await unauthorized.close(); }
});

test("long nicknames remain available inside the menu and regular users have no developer link", async () => {
  const nickname = "긴닉네임".repeat(40);
  const view = await mount({ user: { ...currentUser, nickname } });
  try {
    await click(profile());
    assert.equal(document.querySelector(".profile-menu-identity").textContent, nickname);
    assert.equal(items().some((item) => item.getAttribute("href") === "/developer"), false);
  } finally { await view.close(); }
});

test("guide naming changes retain existing categories, links and topic content", async () => {
  const index = await mount({ Component: HelpIndexPage, props: {} });
  try {
    assert.equal(document.querySelector("h1").textContent, "GuildUp 가이드");
    for (const href of ["/help/general", "/help/attendance", "/help/ranking", "/help/pubg", "/help/pubg/bingo", "/help/pubg/kill-competition"]) {
      assert.ok(document.querySelector(`.help-main a[href="${href}"]`));
    }
    assert.doesNotMatch(document.body.textContent, /사용 설명/);
  } finally { await index.close(); }
  const bingo = await mount({ Component: BingoHelpPage, props: {}, path: "/help/pubg/bingo" });
  try {
    const back = document.querySelector(".activity-back-link");
    assert.equal(back.getAttribute("href"), "/help/pubg"); assert.match(back.textContent, /GuildUp 가이드/);
    assert.equal(document.querySelector("h1").textContent, "빙고판 사용 방법");
    assert.match(document.body.textContent, /AI 봇 기록 제외/);
  } finally { await bingo.close(); }
});
