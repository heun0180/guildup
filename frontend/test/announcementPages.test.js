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

const temp = await mkdtemp(resolve("node_modules/.announcement-pages-"));
const output = resolve(temp, "pages.mjs");
await build({ stdin: { contents: `
  export { default as Page } from "./src/pages/AnnouncementsPage.jsx";
  export { default as Bell } from "./src/components/AnnouncementBell.jsx";
  export { default as Form } from "./src/components/AnnouncementForm.jsx";
  export { default as AdminPage } from "./src/developer/DeveloperAnnouncementsPage.jsx";
  export { default as DeveloperLayout } from "./src/developer/DeveloperLayout.jsx";
  export { AnnouncementProvider } from "./src/announcement/AnnouncementContext.jsx";
  `, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm",
  jsx: "automatic", packages: "external" });
const { Page, Bell, Form, AdminPage, DeveloperLayout, AnnouncementProvider } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));

const user = { id: 9, nickname: "사용자", systemAdmin: false };
const item = { id: 7, title: "Discord 음성 활동 기간 조회 기능 추가", type: "UPDATE", important: false, pinned: false,
  popup: false, published: true, read: false, popupConfirmed: false, newAnnouncement: true, status: "PUBLISHED",
  createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z", publishStartAt: null, publishEndAt: null };
const detail = { announcement: item, content: "본문\n<script>사용자 입력</script>" };
const page = { content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 };
const response = (body, status = 200) => status === 204 ? new Response(null, { status })
  : new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function mount(Component, handle, { url = "/announcements", props = {}, provider = true, strict = false } = {}) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${url}` });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
  dom.window.HTMLElement.prototype.attachEvent = function () {};
  dom.window.HTMLElement.prototype.detachEvent = function () {};
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    if (url === "/api/auth/me") return response(user);
    if (url === "/api/auth/csrf") return response({ token: "test-csrf" });
    const result = await handle(url, options);
    if (result) return result;
    if (url === "/api/announcements/notifications") return response({ unreadCount: 1, recent: [item] });
    if (url === "/api/announcements/popup") return response(null, 204);
    throw new Error(`Unexpected request: ${url}`);
  };
  const root = createRoot(document.getElementById("root"));
  const render = async () => {
    let content = React.createElement(Component, props);
    if (provider) content = React.createElement(AnnouncementProvider, null, content);
    content = React.createElement(BrowserRouter, null, content);
    if (strict) content = React.createElement(React.StrictMode, null, content);
    await act(async () => root.render(content));
  };
  await render();
  return { requests, async navigate(path) {
    await act(async () => { window.history.pushState({}, "", path); window.dispatchEvent(new window.PopStateEvent("popstate")); });
  }, async close() {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  } };
}

test("service list renders types, NEW and read independently and links without a community", async () => {
  const view = await mount(Page, (url) => url.startsWith("/api/announcements?") ? response(page) : null);
  try {
    const card = document.querySelector(".announcement-card");
    assert.equal(card.getAttribute("href"), "/announcements?id=7");
    assert.match(card.textContent, /업데이트/); assert.match(card.textContent, /NEW/); assert.match(card.textContent, /읽지 않음/);
    assert.equal(document.querySelector(".sidebar"), null);
    assert.equal(view.requests.some(({ url }) => /\/read$/.test(url)), false);
    assert.ok(document.querySelector('button[aria-label="GuildUp 알림, 읽지 않은 공지 1개"]'));
  } finally { await view.close(); }
});

test("opening detail marks it read with CSRF, updates the bell and renders content as plain text", async () => {
  let read = false;
  const view = await mount(Page, (url, options) => {
    if (url === "/api/announcements/7") return response(detail);
    if (url === "/api/announcements/7/read") {
      assert.equal(options.method, "POST"); assert.equal(options.headers.get("X-CSRF-Token"), "test-csrf");
      read = true; return response({ ...detail, announcement: { ...item, read: true } });
    }
    if (url === "/api/announcements/notifications") return response({ unreadCount: read ? 0 : 1, recent: [{ ...item, read }] });
  }, { url: "/announcements?id=7", strict: true });
  try {
    assert.equal(read, true); assert.match(document.querySelector(".announcement-meta").textContent, /읽음/);
    assert.match(document.querySelector(".announcement-meta").textContent, /NEW/);
    assert.match(document.querySelector(".announcement-body").textContent, /<script>/);
    assert.equal(document.querySelector(".announcement-body script"), null);
    assert.equal(document.querySelector(".announcement-count"), null);
  } finally { await view.close(); }
});

test("navigation between list and detail never renders the previous response in the new view", async () => {
  const view = await mount(Page, (url) => {
    if (url.startsWith("/api/announcements?")) return response(page);
    if (url === "/api/announcements/7") return response(detail);
    if (url === "/api/announcements/7/read") return response({ ...detail, announcement: { ...item, read: true } });
  }, { provider: false });
  try {
    await act(async () => document.querySelector(".announcement-card").click());
    assert.equal(document.querySelector(".announcement-detail h2").textContent, item.title);
    await act(async () => document.querySelector(".announcement-detail > a").click());
    assert.equal(document.querySelector(".announcement-detail"), null);
    assert.ok(document.querySelector(".announcement-card"));
  } finally { await view.close(); }
});

test("late detail responses cannot overwrite a newly selected announcement", async () => {
  let finish;
  const view = await mount(Page, (url) => {
    if (url === "/api/announcements/7") return new Promise((resolve) => { finish = resolve; });
    if (url === "/api/announcements/8") return response({ content: "다음 본문", announcement: { ...item, id: 8, title: "다음 공지", read: true } });
  }, { url: "/announcements?id=7" });
  try {
    await view.navigate("/announcements?id=8");
    await act(async () => finish(response(detail)));
    assert.equal(document.querySelector(".announcement-detail h2").textContent, "다음 공지");
    assert.equal(view.requests.some(({ url }) => url === "/api/announcements/7/read"), false);
  } finally { await view.close(); }
});

test("failed read leaves the detail visible and retry records the read", async () => {
  let failure = true;
  const view = await mount(Page, (url) => {
    if (url === "/api/announcements/7") return response(detail);
    if (url === "/api/announcements/7/read") return failure ? response({ message: "failed" }, 400)
      : response({ ...detail, announcement: { ...item, read: true } });
  }, { url: "/announcements?id=7", provider: false });
  try {
    assert.ok(document.querySelector(".announcement-detail"));
    assert.match(document.querySelector('[role="alert"]').textContent, /읽음 기록/);
    failure = false;
    await act(async () => document.querySelector('[role="alert"] button').click());
    assert.equal(document.querySelector('[role="alert"]'), null);
    assert.match(document.querySelector(".announcement-read").textContent, /읽음/);
  } finally { await view.close(); }
});

test("bell shows recent announcements, read status and all link and closes with Escape", async () => {
  const view = await mount(Bell, () => null, { props: { user } });
  try {
    const trigger = document.querySelector(".announcement-bell-button");
    await act(async () => trigger.click());
    const dropdown = document.querySelector(".announcement-dropdown");
    assert.ok(dropdown); assert.match(dropdown.textContent, /업데이트/); assert.match(dropdown.textContent, /읽지 않음/);
    assert.equal(dropdown.querySelector(".announcement-all").getAttribute("href"), "/announcements");
    await act(async () => document.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape" })));
    assert.equal(document.querySelector(".announcement-dropdown"), null);
    assert.equal(trigger.getAttribute("aria-expanded"), "false");
  } finally { await view.close(); }
});

test("unavailable or malformed notification APIs cannot break the global header", async () => {
  const view = await mount(Bell, (url) => url === "/api/announcements/notifications" || url === "/api/announcements/popup"
    ? response([]) : null, { props: { user } });
  try {
    assert.ok(document.querySelector(".announcement-bell-button"));
    assert.equal(document.querySelector("dialog"), null);
    await act(async () => document.querySelector(".announcement-bell-button").click());
    assert.match(document.querySelector('[role="alert"]').textContent, /응답을 확인하지 못했습니다/);
    assert.ok(document.querySelector(".announcement-all"));
  } finally { await view.close(); }
});

test("popup confirmation persists and is not repeated on later refresh", async () => {
  let confirmed = false;
  const popup = { ...detail, announcement: { ...item, important: true, popup: true } };
  const view = await mount(Bell, (url, options) => {
    if (url === "/api/announcements/popup") return confirmed ? response(null, 204) : response(popup);
    if (url.endsWith("/popup-confirmation")) {
      assert.equal(options.method, "POST"); assert.ok(options.headers.get("X-CSRF-Token"));
      confirmed = true; return response({ ...popup, announcement: { ...popup.announcement, read: true, popupConfirmed: true } });
    }
  }, { props: { user }, strict: true });
  try {
    assert.equal(document.querySelector("dialog").open, true);
    await act(async () => document.querySelector("dialog button").click());
    assert.equal(confirmed, true); assert.equal(document.querySelector("dialog"), null);
    await act(async () => window.dispatchEvent(new window.Event("focus")));
    assert.equal(document.querySelector("dialog"), null);
  } finally { await view.close(); }
});

test("scheduled and expired administrator entries render all statuses and support editing", async () => {
  const view = await mount(AdminPage, (url) => {
    if (url.startsWith("/api/developer/announcements?")) return response({ ...page, content:
      ["PRIVATE", "SCHEDULED", "PUBLISHED", "ENDED"].map((status, id) => ({ ...item, id, status })) });
    if (url === "/api/developer/announcements/0") return response(detail);
  }, { provider: false });
  try {
    assert.match(document.body.textContent, /비공개/); assert.match(document.body.textContent, /예약/);
    assert.match(document.body.textContent, /게시중/); assert.match(document.body.textContent, /종료/);
    await act(async () => document.querySelector(".announcement-admin-card button").click());
    assert.ok(document.querySelector('form[aria-label="GuildUp 공지 작성"]'));
    assert.equal(document.querySelector("textarea").value, detail.content);
  } finally { await view.close(); }
});

test("confirming a popup immediately updates the read badge in an already open list", async () => {
  let confirmed = false;
  const view = await mount(Page, (url) => {
    if (url.startsWith("/api/announcements?")) return response(page);
    if (url === "/api/announcements/popup") return confirmed ? response(null, 204)
      : response({ ...detail, announcement: { ...item, important: true, popup: true } });
    if (url.endsWith("/popup-confirmation")) {
      confirmed = true; return response({ ...detail, announcement: { ...item, read: true, popupConfirmed: true } });
    }
  });
  try {
    assert.match(document.querySelector(".announcement-card .announcement-read").textContent, /읽지 않음/);
    await act(async () => document.querySelector("dialog button").click());
    assert.equal(document.querySelector(".announcement-card .announcement-read").textContent, "읽음");
  } finally { await view.close(); }
});

test("form defaults to private and popup disabled until important", async () => {
  const view = await mount(Form, () => null, { props: { busy: false, onSave() {}, onCancel() {} }, provider: false });
  try {
    const [published, important, pinned, popup] = document.querySelectorAll('input[type="checkbox"]');
    assert.equal(published.checked, false); assert.equal(pinned.checked, false); assert.equal(popup.disabled, true);
    await act(async () => important.click()); assert.equal(popup.disabled, false);
    await act(async () => popup.click()); assert.equal(popup.checked, true);
    await act(async () => important.click()); assert.equal(popup.checked, false); assert.equal(popup.disabled, true);
  } finally { await view.close(); }
});

test("regular user cannot render the developer announcements page or invoke its APIs", async () => {
  const view = await mount(DeveloperLayout, () => null, { url: "/developer/announcements", provider: false });
  try {
    assert.match(document.body.textContent, /접근 권한이 없습니다/);
    assert.equal(document.querySelector(".developer-sidebar"), null);
    assert.equal(view.requests.some(({ url }) => url.startsWith("/api/developer/")), false);
  } finally { await view.close(); }
});
