import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";

// React의 input 이벤트 지원 검사는 모듈 로드 시점에 수행된다.
const bootstrap = new JSDOM("<html></html>");
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
Object.defineProperty(globalThis, "window", { value: bootstrap.window, configurable: true });
Object.defineProperty(globalThis, "document", { value: bootstrap.window.document, configurable: true });
const { createRoot } = await import("react-dom/client");
if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow); else delete globalThis.window;
if (originalDocument) Object.defineProperty(globalThis, "document", originalDocument); else delete globalThis.document;
bootstrap.window.close();

const temp = await mkdtemp(resolve("node_modules/.support-test-"));
const output = resolve(temp, "support.mjs");
await build({ stdin: { contents: `
  export { default as App } from "./src/App.jsx";
  export { default as Header } from "./src/components/AppHeader.jsx";
  export { default as Sidebar } from "./src/components/Sidebar.jsx";
  export { default as Support } from "./src/pages/FeedbackPage.jsx";
  export { default as DeveloperSupport } from "./src/developer/DeveloperFeedbackPage.jsx";
  `, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external" });
const { App, Header, Sidebar, Support, DeveloperSupport } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));
const user = { id: 9, nickname: "애플", systemAdmin: false };
const item = { id: 17, type: "SERVICE", title: "로그인 문제", userId: 9, authorNickname: "애플",
  createdAt: "2026-10-07T00:00:00Z", status: "RECEIVED", communityId: null, communityName: null };
const page = { content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 };
const detail = { feedback: item, content: "문의 내용", pageRoute: "/account.html", answer: null, answeredAt: null, version: 0 };
const response = (body, status = 200) => status === 204 ? new Response(null, { status })
  : new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function mount({ path = "/support", Component = App, props = {}, handle = () => null, sourceHeader = false } = {}) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${path}` });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const requests = [];
  const oldFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    const override = await handle(url, options);
    if (override) return override;
    if (url === "/api/auth/me") return response(user);
    if (url === "/api/auth/csrf") return response({ token: "csrf-test" });
    if (url === "/api/announcements/notifications") return response({ unreadCount: 0, recent: [] });
    if (url === "/api/announcements/popup") return response(null, 204);
    if (/^\/api\/(?:developer\/)?feedback\?/.test(url)) return response(page);
    if (/^\/api\/(?:developer\/)?feedback\/17$/.test(url)) return response(detail);
    if (url === "/api/feedback" && options.method === "POST") return response({ message: "문의가 접수되었습니다." });
    throw new Error(`Unexpected request: ${url}`);
  };
  const root = createRoot(document.getElementById("root"));
  const content = sourceHeader ? React.createElement(Routes, null,
    React.createElement(Route, { path: "*", element: React.createElement(Header, { actions: true }) }),
    React.createElement(Route, { path: "/support", element: React.createElement(Support) })) : React.createElement(Component, props);
  await act(async () => root.render(React.createElement(BrowserRouter, { window: dom.window }, content)));
  return { requests, async close() {
    await act(async () => root.unmount()); globalThis.fetch = oldFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  } };
}
async function click(selector) {
  const el = document.querySelector(selector); assert.ok(el, selector);
  await act(async () => el.click());
}
async function input(selector, value) {
  const el = document.querySelector(selector); assert.ok(el, selector);
  await act(async () => {
    const proto = el.tagName === "TEXTAREA" ? window.HTMLTextAreaElement.prototype
      : el.tagName === "SELECT" ? window.HTMLSelectElement.prototype : window.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value);
    el.dispatchEvent(new window.Event(el.tagName === "SELECT" ? "change" : "input", { bubbles: true }));
  });
}
async function submit() {
  await input("#feedback-title", "제목"); await input("#feedback-content", "내용");
  await act(async () => document.querySelector(".feedback-form").dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true })));
}

test("direct support and legacy URLs load independently of CommunityProvider and submit without community", async () => {
  for (const path of ["/support", "/feedback.html"]) {
    const view = await mount({ path });
    try {
      assert.equal(document.querySelector("h1").textContent, "문의 / 건의");
      assert.equal(document.querySelector(".sidebar"), null);
      assert.equal(document.querySelectorAll("#feedback-type option").length, 4);
      assert.equal(document.querySelector('.feedback-form button[type="submit"]').disabled, false);
      await submit();
      const request = view.requests.find((r) => r.url === "/api/feedback" && r.options.method === "POST");
      assert.ok(request);
      assert.deepEqual(JSON.parse(request.options.body), { type: "SERVICE", title: "제목", content: "내용", communityId: null, pageRoute: path });
      assert.equal(new Headers(request.options.headers).get("X-CSRF-Token"), "csrf-test");
      assert.match(document.querySelector('[role="status"]').textContent, /문의가 접수/);
      assert.equal(document.querySelector("#feedback-title").value, "");
      assert.equal(view.requests.some((r) => r.url.startsWith("/api/communities")), false);
    } finally { await view.close(); }
  }
});

test("profile menu from community selection, account and community routes preserves only safe optional context", async () => {
  for (const [path, communityId] of [["/communities.html", null], ["/account.html", null],
    ["/members.html?communityId=12&access_token=secret#refresh_token=secret", 12]]) {
    const view = await mount({ path, sourceHeader: true });
    try {
      await click(".profile-menu-trigger"); await click('a[role="menuitem"][href="/support"]');
      assert.equal(window.location.pathname, "/support");
      await submit();
      const payload = JSON.parse(view.requests.find((r) => r.url === "/api/feedback" && r.options.method === "POST").options.body);
      assert.equal(payload.communityId, communityId);
      assert.equal(payload.pageRoute, path.split(/[?#]/)[0]);
      assert.doesNotMatch(JSON.stringify(payload), /secret|token/);
    } finally { await view.close(); }
  }
});

test("community sidebar has no feedback navigation", async () => {
  const view = await mount({ Component: Sidebar, props: { community: { name: "커뮤니티", role: "MEMBER", games: [] }, communityId: "12" } });
  try { assert.doesNotMatch(document.body.textContent, /문의|건의/); assert.equal(document.querySelector('a[href*="feedback"]'), null); }
  finally { await view.close(); }
});

test("own detail displays route, content and answer without management controls", async () => {
  const view = await mount();
  try {
    await click(".support-title");
    assert.match(document.querySelector(".support-detail").textContent, /문의 내용|\/account.html|User ID: 9/);
    assert.equal(document.querySelector("#support-answer"), null);
    assert.equal(document.querySelector("#support-status"), null);
  } finally { await view.close(); }
});

test("developer view lists author and optional community and saves answer and status with version", async () => {
  const view = await mount({ Component: DeveloperSupport, path: "/developer/feedback", handle: (url, options) => {
    if (url === "/api/developer/feedback/17" && options.method === "PUT") return response({
      ...detail, feedback: { ...item, status: "ANSWERED" }, answer: "답변입니다", version: 1,
    });
    return null;
  } });
  try {
    assert.match(document.querySelector("thead").textContent, /작성자/);
    await click(".support-title");
    await input("#support-status", "ANSWERED"); await input("#support-answer", "답변입니다");
    await act(async () => document.querySelector('form[aria-label="문의 답변 및 상태 관리"]').dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true })));
    const request = view.requests.find((r) => r.options.method === "PUT");
    assert.deepEqual(JSON.parse(request.options.body), { status: "ANSWERED", answer: "답변입니다", version: 0 });
    assert.match(document.querySelector(".support-detail").textContent, /답변 완료|답변입니다/);
  } finally { await view.close(); }
});

test("support and developer routes are included in static host entry generation", async () => {
  const { extensionlessRoutes } = await import("../scripts/production-build.mjs");
  const routes = extensionlessRoutes(await readFile("src/App.jsx", "utf8"));
  assert.ok(routes.includes("/support")); assert.ok(routes.includes("/developer/feedback"));
});
