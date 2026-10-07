import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { BrowserRouter } from "react-router-dom";

// React DOM가 input 이벤트 지원을 감지하도록 import 전에 브라우저 문서를 제공한다.
const bootstrap = new JSDOM("<div></div>");
const bootstrapWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
const bootstrapDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
Object.defineProperty(globalThis, "window", { value: bootstrap.window, configurable: true });
Object.defineProperty(globalThis, "document", { value: bootstrap.window.document, configurable: true });
const { createRoot } = await import("react-dom/client");
if (bootstrapWindow) Object.defineProperty(globalThis, "window", bootstrapWindow); else delete globalThis.window;
if (bootstrapDocument) Object.defineProperty(globalThis, "document", bootstrapDocument); else delete globalThis.document;
bootstrap.window.close();

const temp = await mkdtemp(resolve("node_modules/.auth-pages-"));
const output = resolve(temp, "pages.mjs");
await build({ stdin: { contents: `
  export { default as LoginPage } from "./src/pages/LoginPage.jsx";
  export { default as AccountSettingsPage } from "./src/pages/AccountSettingsPage.jsx";
  export { default as CommunitiesPage } from "./src/pages/CommunitiesPage.jsx";
`, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external" });
const { LoginPage, AccountSettingsPage, CommunitiesPage } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));
const user = { id: 15, nickname: "기존 사용자", systemAdmin: false };
const response = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });

async function mount({ Component = LoginPage, props = {}, path = "/login.html", handle = () => null } = {}) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${path}` });
  const redirects = [], requests = [];
  const location = new Proxy({}, { get(_target, key) {
    if (key === "replace" || key === "assign") return (url) => redirects.push(url);
    return Reflect.get(dom.window.location, key, dom.window.location);
  } });
  const testWindow = new Proxy(dom.window, { get(target, key) {
    if (key === "location") return location;
    const value = Reflect.get(target, key, target);
    return ["addEventListener", "removeEventListener", "setInterval", "clearInterval"].includes(key) ? value.bind(target) : value;
  } });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: testWindow, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    const result = await handle(url, options);
    if (result) return result;
    if (url === "/api/auth/csrf") return response({ token: "anonymous-or-current-session-token" });
    if (url === "/api/auth/me") return Component === LoginPage ? response({ message: "로그인이 필요합니다." }, 401) : response(user);
    throw new Error(`Unexpected request: ${url}`);
  };
  const root = createRoot(document.getElementById("root"));
  async function close() {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  }
  try { await act(async () => root.render(React.createElement(BrowserRouter, { window: dom.window }, React.createElement(Component, props)))); }
  catch (error) { await close(); throw error; }
  return { requests, redirects, close };
}
async function fill(name, value) {
  const input = document.querySelector(`[name="${name}"]`);
  assert.ok(input);
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set.call(input, value);
    input.dispatchEvent(new window.Event("input", { bubbles: true }));
  });
}
async function submit() { await act(async () => document.querySelector("form").dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }))); }
async function clickText(text) {
  const button = [...document.querySelectorAll("button")].find((item) => item.textContent === text);
  assert.ok(button, text); await act(async () => button.click());
}

const withdrawalAccount = { user, email: "private@example.com", emailVerified: false, discordConnected: true, memberLinkConflicts: [] };
const withdrawalCheck = { canWithdraw: true, verificationMethod: "PASSWORD", verified: false, ownedCommunities: [] };

test("withdrawal requires notice acknowledgement, password verification and a separate final click", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(withdrawalAccount);
    if (url === "/api/account/withdrawal/check") return response(withdrawalCheck);
    if (url === "/api/account/withdrawal/verify" || url === "/api/account") return new Response(null, { status: 204 });
    return null;
  } });
  try {
    await clickText("회원탈퇴");
    assert.equal(view.requests.filter(({ options }) => options.method === "DELETE").length, 0);
    assert.equal([...document.querySelectorAll("button")].find((button) => button.textContent === "다음").disabled, true);
    await act(async () => document.querySelector('.withdrawal-ack input').click());
    await clickText("다음");
    await fill("withdrawalPassword", "GuildUp123!"); await submit();
    assert.ok(document.querySelector('[aria-label="회원탈퇴 최종 확인"]'));
    assert.equal(document.querySelector('[name="withdrawalPassword"]'), null);
    assert.equal(view.requests.filter(({ options }) => options.method === "DELETE").length, 0);
    await clickText("회원탈퇴");
    const request = view.requests.find(({ options }) => options.method === "DELETE");
    assert.equal(request.url, "/api/account");
    assert.deepEqual(JSON.parse(request.options.body), { acknowledged: true });
    assert.equal(new Headers(request.options.headers).get("X-CSRF-Token"), "anonymous-or-current-session-token");
    assert.deepEqual(view.redirects, ["/login.html?withdrawn=true"]);
  } finally { await view.close(); }
});

test("withdrawal lists all owned communities and blocks progress", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(withdrawalAccount);
    if (url === "/api/account/withdrawal/check") return response({ ...withdrawalCheck, canWithdraw: false,
      ownedCommunities: [{ communityId: 3, communityName: "치즈 클랜" }, { communityId: 4, communityName: "ABC 클랜" }] });
    return null;
  } });
  try {
    await clickText("회원탈퇴");
    assert.match(document.querySelector(".account-danger").textContent, /치즈 클랜/);
    assert.match(document.querySelector(".account-danger").textContent, /ABC 클랜/);
    assert.equal(document.querySelector('.withdrawal-ack'), null);
    await clickText("취소");
    assert.equal(view.requests.filter(({ options }) => options.method === "DELETE").length, 0);
  } finally { await view.close(); }
});

test("wrong withdrawal password leaves user on verification step and clears the password", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(withdrawalAccount);
    if (url === "/api/account/withdrawal/check") return response(withdrawalCheck);
    if (url === "/api/account/withdrawal/verify") return response({ code: "INVALID_CURRENT_PASSWORD", message: "현재 비밀번호가 올바르지 않습니다." }, 400);
    return null;
  } });
  try {
    await clickText("회원탈퇴"); await act(async () => document.querySelector('.withdrawal-ack input').click());
    await clickText("다음"); await fill("withdrawalPassword", "wrong"); await submit();
    assert.equal(document.querySelector('[name="withdrawalPassword"]').value, "");
    assert.equal(document.querySelector('[aria-label="회원탈퇴 최종 확인"]'), null);
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("Discord-only withdrawal starts a CSRF-protected separate OAuth verification", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response({ ...withdrawalAccount, email: null });
    if (url === "/api/account/withdrawal/check") return response({ ...withdrawalCheck, verificationMethod: "DISCORD" });
    if (url === "/api/auth/discord/withdrawal") return response({ authorizationUrl: "https://discord.com/oauth2/authorize?state=withdrawal" });
    return null;
  } });
  try {
    await clickText("회원탈퇴"); await act(async () => document.querySelector('.withdrawal-ack input').click());
    await clickText("다음");
    assert.equal(document.querySelector('[name="withdrawalPassword"]'), null);
    await clickText("Discord로 본인 확인");
    const request = view.requests.find(({ url }) => url === "/api/auth/discord/withdrawal");
    assert.equal(request.options.method, "POST");
    assert.ok(new Headers(request.options.headers).get("X-CSRF-Token"));
    assert.equal(view.requests.filter(({ options }) => options.method === "DELETE").length, 0);
  } finally { await view.close(); }
});

test("OAuth return still requires acknowledgement and final confirmation without automatic deletion", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html?withdrawalVerified=true", handle: (url) => {
    if (url === "/api/auth/account") return response(withdrawalAccount);
    if (url === "/api/account/withdrawal/check") return response({ ...withdrawalCheck, verificationMethod: "DISCORD", verified: true });
    return null;
  } });
  try {
    assert.ok(document.querySelector('.withdrawal-ack'));
    assert.equal(document.querySelector('[aria-label="회원탈퇴 최종 확인"]'), null);
    await act(async () => document.querySelector('.withdrawal-ack input').click()); await clickText("다음");
    assert.ok(document.querySelector('[aria-label="회원탈퇴 최종 확인"]'));
    assert.equal(view.requests.filter(({ options }) => options.method === "DELETE").length, 0);
  } finally { await view.close(); }
});

for (const signup of [false, true]) {
  test(`${signup ? "signup" : "login"} sends public authentication through CSRF and keeps Discord available`, async () => {
    const view = await mount({ props: { signup }, handle: (url) => url === (signup ? "/api/auth/signup" : "/api/auth/login") ? response(user, signup ? 201 : 200) : null });
    try {
      assert.equal(document.querySelector('[href="/api/auth/discord/authorize"]').textContent.trim(), "Discord로 계속하기");
      assert.equal(document.body.textContent.includes("비밀번호 찾기"), false);
      await fill("email", "new@example.com"); await fill("password", "GuildUp123!");
      if (signup) { await fill("passwordConfirmation", "GuildUp123!"); await fill("nickname", "신규 사용자"); }
      await submit();
      const mutation = view.requests.find(({ url }) => url === (signup ? "/api/auth/signup" : "/api/auth/login"));
      assert.ok(mutation);
      assert.equal(new Headers(mutation.options.headers).get("X-CSRF-Token"), "anonymous-or-current-session-token");
      assert.equal(JSON.parse(mutation.options.body).email, "new@example.com");
      assert.deepEqual(view.redirects, ["/communities.html"]);
      if (signup) assert.ok(document.querySelector(".auth-existing-account"));
    } finally { await view.close(); }
  });
}

test("signup mismatch prevents mutation and duplicate-email response is understandable", async () => {
  const view = await mount({ props: { signup: true }, handle: (url) => url === "/api/auth/signup"
    ? response({ code: "EMAIL_ALREADY_USED", message: "이미 사용 중인 이메일입니다." }, 409) : null });
  try {
    await fill("email", "used@example.com"); await fill("password", "GuildUp123!"); await fill("passwordConfirmation", "Mismatch123!"); await fill("nickname", "닉네임");
    await submit(); assert.match(document.querySelector('[role="alert"]').textContent, /비밀번호가 일치하지 않습니다/);
    assert.equal(view.requests.some(({ url }) => url === "/api/auth/signup"), false);
    await fill("passwordConfirmation", "GuildUp123!"); await submit();
    assert.equal(document.querySelector('[role="alert"]').textContent, "이미 사용 중인 이메일입니다.");
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("wrong email password stays on login and displays the server error", async () => {
  const view = await mount({ handle: (url) => url === "/api/auth/login" ? response({ message: "이메일 또는 비밀번호가 올바르지 않습니다." }, 401) : null });
  try {
    await fill("email", "user@example.com"); await fill("password", "Wrong123!"); await submit();
    assert.equal(document.querySelector('[role="alert"]').textContent, "이메일 또는 비밀번호가 올바르지 않습니다.");
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("existing Discord user's settings add email credentials instead of signing up", async () => {
  let added = false;
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response({ user, discordConnected: true, discordUsername: "apple", email: added ? "same@example.com" : null, emailVerified: false, memberLinkConflicts: [] });
    if (url === "/api/auth/credentials") { added = true; return response(user, 201); }
    return null;
  } });
  try {
    await clickText("이메일 로그인 추가");
    await fill("email", "same@example.com"); await fill("password", "GuildUp123!"); await fill("passwordConfirmation", "GuildUp123!"); await submit();
    assert.ok(view.requests.find(({ url }) => url === "/api/auth/credentials"));
    assert.equal(view.requests.some(({ url }) => url === "/api/auth/signup"), false);
    assert.equal(document.querySelector(".account-email").textContent, "same@example.com");
    assert.match(document.querySelector(".auth-success").textContent, /현재 계정과 커뮤니티 기록은 그대로/);
  } finally { await view.close(); }
});

test("email user starts Discord LINK_ACCOUNT with POST and CSRF", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response({ user, email: "email@example.com", emailVerified: false, discordConnected: false, memberLinkConflicts: [] });
    if (url === "/api/auth/discord/link") return response({ authorizationUrl: "https://discord.com/oauth2/authorize?state=checked" });
    return null;
  } });
  try {
    await clickText("Discord 연결");
    const link = view.requests.find(({ url }) => url === "/api/auth/discord/link");
    assert.equal(link.options.method, "POST");
    assert.equal(new Headers(link.options.headers).get("X-CSRF-Token"), "anonymous-or-current-session-token");
    assert.equal(view.requests.some(({ url }) => url === "/api/auth/discord/authorize"), false);
    assert.deepEqual(view.redirects, ["https://discord.com/oauth2/authorize?state=checked"]);
    assert.match(document.body.textContent, /이메일 소유 인증은 아직 제공되지 않습니다/);
  } finally { await view.close(); }
});

test("Discord collision offers existing-account guidance without a merge action", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html?oauthError=DISCORD_ACCOUNT_CONFLICT", handle: (url) => url === "/api/auth/account"
    ? response({ user, email: "email@example.com", discordConnected: false, memberLinkConflicts: [] }) : null });
  try {
    assert.match(document.querySelector('[role="alert"]').textContent, /이미 다른 GuildUp 계정에 연결된 Discord 계정/);
    assert.match(document.querySelector('[role="alert"]').textContent, /기존 계정으로 로그인/);
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("Discord absent community page keeps native memberships and shows account linking", async () => {
  const view = await mount({ Component: CommunitiesPage, path: "/communities.html", handle: (url) => {
    if (url === "/api/auth/me/communities") return response([{ id: 1, name: "기존 커뮤니티", gameName: "PUBG", memberCount: 1 }]);
    if (url === "/api/community-discoveries/discord") return response([]);
    if (url === "/api/auth/account") return response({ discordConnected: false });
    return null;
  } });
  try {
    assert.ok(document.querySelector('[href="/community-dashboard.html?communityId=1"]'));
    assert.ok(document.querySelector('.discord-account-notice [href="/account.html"]'));
    assert.equal(document.querySelector('[role="alert"]'), null);
  } finally { await view.close(); }
});
