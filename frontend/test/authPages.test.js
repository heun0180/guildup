import test, { after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile } from "node:fs/promises";
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
  export { default as EmailVerificationPage } from "./src/pages/EmailVerificationPage.jsx";
  export { default as ForgotPasswordPage } from "./src/pages/ForgotPasswordPage.jsx";
  export { default as PasswordResetPage } from "./src/pages/PasswordResetPage.jsx";
  export { clearResetLinkToken } from "./src/passwordReset.js";
  export { clearVerificationLinkToken } from "./src/emailVerification.js";
`, resolveDir: process.cwd() }, outfile: output, bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external" });
const { LoginPage, AccountSettingsPage, CommunitiesPage, EmailVerificationPage, clearVerificationLinkToken, ForgotPasswordPage, PasswordResetPage, clearResetLinkToken } = await import(pathToFileURL(output));
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
    return ["addEventListener", "removeEventListener", "dispatchEvent", "setInterval", "clearInterval"].includes(key) ? value.bind(target) : value;
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
    if (url === "/api/account/profile") return response({ nickname: user.nickname, birthDate: null, avatarUrl: null });
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
async function submit(selector = "form:not(#account-profile-form)") {
  await act(async () => document.querySelector(selector).dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true })));
}
async function clickText(text) {
  const button = [...document.querySelectorAll("button")].find((item) => item.textContent === text);
  assert.ok(button, text); await act(async () => button.click());
}

const withdrawalAccount = { user, email: "private@example.com", emailVerified: false, discordConnected: true, memberLinkConflicts: [] };
const profileAccount = { ...withdrawalAccount, discordUsername: "apple_123", discordDisplayName: "Discord Apple",
  discordAvatarUrl: "https://cdn.discordapp.com/avatars/123/hash.webp", canDisconnectDiscord: true,
  createdAt: "2026-10-07T00:00:00Z" };
const profileData = { nickname: "GuildUp Apple", birthDate: null, avatarUrl: profileAccount.discordAvatarUrl };

test("login cooldown counts down, blocks repeated submits, keeps Discord available and resumes without reload", async () => {
  let now = 1_000_000, tick;
  const previousNow = Date.now; Date.now = () => now;
  let attempts = 0;
  const view = await mount({ handle: (url) => {
    if (url === "/api/auth/login") {
      attempts += 1;
      return attempts === 1 ? new Response('{"code":"LOGIN_RATE_LIMITED","message":"limited"}', { status: 429, headers: { "Retry-After": "3" } })
        : response({ code: "INVALID_CREDENTIALS", message: "이메일 또는 비밀번호가 올바르지 않습니다." }, 401);
    }
    return null;
  } });
  const oldInterval = window.setInterval, oldClear = window.clearInterval;
  window.setInterval = (callback) => { tick = callback; return 1; }; window.clearInterval = () => {};
  try {
    await fill("email", "private@example.com"); await fill("password", "GuildUp123!");
    await submit();
    assert.match(document.querySelector('[role="alert"]').textContent, /일시적으로 제한/);
    assert.match(document.querySelector('[role="status"]').textContent, /3초/);
    assert.equal(document.querySelector(".auth-form button").disabled, true);
    assert.equal(document.querySelector('a[href="/api/auth/discord/authorize"]').getAttribute("aria-disabled"), "false");
    await submit(); await submit(); assert.equal(attempts, 1);
    now += 1000; await act(async () => tick());
    assert.match(document.querySelector('[role="status"]').textContent, /2초/);
    now += 2000; await act(async () => tick());
    assert.equal(document.querySelector(".auth-form button").disabled, false);
    await submit(); assert.equal(attempts, 2);
    assert.deepEqual(view.redirects, []);
  } finally { Date.now = previousNow; window.setInterval = oldInterval; window.clearInterval = oldClear; await view.close(); }
});

test("same-tick repeated login submits reserve one request and missing Retry-After uses a short wait", async () => {
  let resolveLogin, attempts = 0;
  const view = await mount({ handle: (url) => {
    if (url === "/api/auth/login") { attempts++; return new Promise((resolveRequest) => { resolveLogin = resolveRequest; }); }
    return null;
  } });
  try {
    await fill("email", "private@example.com"); await fill("password", "GuildUp123!");
    await act(async () => {
      const form = document.querySelector(".auth-form");
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
    });
    assert.equal(attempts, 1);
    await act(async () => resolveLogin(response({ code: "LOGIN_RATE_LIMITED", message: "limited" }, 429)));
    assert.match(document.querySelector('[role="status"]').textContent, /5초/);
    await submit(); assert.equal(attempts, 1);
  } finally { await view.close(); }
});
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
      assert.equal(document.querySelector('[href="/api/auth/discord/authorize"]').textContent.trim(), "디스코드 로그인");
      const forgot = document.querySelector('a[href="/forgot-password.html"]');
      if (signup) assert.equal(forgot, null); else assert.equal(forgot.textContent, "비밀번호를 잊으셨나요?");
      assert.ok(document.querySelector('[href="/api/auth/discord/authorize"] svg[aria-hidden="true"]'));
      await fill("email", "new@example.com"); await fill("password", "GuildUp123!");
      if (signup) { await fill("passwordConfirmation", "GuildUp123!"); await fill("nickname", "신규 사용자"); }
      await submit();
      const mutation = view.requests.find(({ url }) => url === (signup ? "/api/auth/signup" : "/api/auth/login"));
      assert.ok(mutation);
      assert.equal(new Headers(mutation.options.headers).get("X-CSRF-Token"), "anonymous-or-current-session-token");
      assert.equal(JSON.parse(mutation.options.body).email, "new@example.com");
      assert.deepEqual(view.redirects, [signup ? "/email-verification.html" : "/communities.html"]);
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
    assert.match(document.body.textContent, /안전한 계정 이용을 위해 이메일 인증을 완료/);
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

test("account has four sections with separate GuildUp and Discord names and optional birthday", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/profile") return response(profileData);
    return null;
  } });
  try {
    assert.equal(document.querySelector("h1").textContent, "계정");
    assert.deepEqual([...document.querySelectorAll(".account-main > .account-section > .account-section-heading > h2")]
      .map((heading) => heading.textContent), ["프로필", "로그인 및 보안", "연결된 계정", "계정 관리"]);
    assert.equal(document.querySelector('[name="profileNickname"]').value, "GuildUp Apple");
    assert.equal(document.querySelector('[name="birthDate"]').required, false);
    assert.equal(document.querySelector('[name="birthDate"]').value, "");
    assert.equal(document.querySelector(".account-profile-image img").src, profileData.avatarUrl);
    assert.match(document.querySelector(".account-discord-identity").textContent, /Discord Apple.*@apple_123/);
    assert.ok(document.querySelector(".account-management time"));
    assert.equal([...document.querySelectorAll("button")].some((item) => /업로드|비밀번호 변경/.test(item.textContent)), false);
    assert.equal(document.querySelectorAll('[name="phone"], [name="gender"], [name="realName"], [name="address"]').length, 0);
  } finally { await view.close(); }
});

test("profile saves only nickname and birthday with CSRF and updates the header immediately", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url, options) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/profile") return response(options.method === "PATCH"
      ? { ...profileData, ...JSON.parse(options.body) } : profileData);
    return null;
  } });
  try {
    await fill("profileNickname", "  애플 Apple_123  "); await fill("birthDate", "1993-01-01");
    await submit("#account-profile-form");
    const request = view.requests.find(({ url, options }) => url === "/api/account/profile" && options.method === "PATCH");
    assert.deepEqual(JSON.parse(request.options.body), { nickname: "애플 Apple_123", birthDate: "1993-01-01" });
    assert.equal(new Headers(request.options.headers).get("X-CSRF-Token"), "anonymous-or-current-session-token");
    assert.equal(document.querySelector(".profile-menu-name").textContent, "애플 Apple_123");
    assert.equal(document.querySelector('[name="profileNickname"]').value, "애플 Apple_123");
    assert.match(document.querySelector(".account-discord-identity").textContent, /Discord Apple/);
    assert.match(document.querySelector(".auth-success").textContent, /프로필 변경사항을 저장/);
  } finally { await view.close(); }
});

test("birthday can be removed without requiring other private information", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url, options) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/profile") return response(options.method === "PATCH"
      ? { ...profileData, ...JSON.parse(options.body) } : { ...profileData, birthDate: "1993-01-01" });
    return null;
  } });
  try {
    await fill("birthDate", ""); await submit("#account-profile-form");
    const request = view.requests.find(({ options }) => options.method === "PATCH");
    assert.equal(JSON.parse(request.options.body).birthDate, null);
    assert.equal(document.querySelector('[name="birthDate"]').value, "");
  } finally { await view.close(); }
});

test("profile validation blocks blank nickname and failed server validation keeps draft values", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url, options) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/profile") return options.method === "PATCH"
      ? response({ code: "INVALID_BIRTH_DATE", message: "생년월일은 미래가 아닌 실제 날짜로 입력해 주세요." }, 400) : response(profileData);
    return null;
  } });
  try {
    await fill("profileNickname", "   "); await submit("#account-profile-form");
    assert.equal(view.requests.some(({ options }) => options.method === "PATCH"), false);
    assert.match(document.querySelector('[role="alert"]').textContent, /닉네임/);
    assert.equal(document.querySelector('[name="profileNickname"]').maxLength, 50);
    await fill("profileNickname", "저장할 이름"); await fill("birthDate", "2999-01-01"); await submit("#account-profile-form");
    assert.match(document.querySelector('[role="alert"]').textContent, /미래가 아닌/);
    assert.equal(document.querySelector('[name="profileNickname"]').value, "저장할 이름");
    assert.equal(document.querySelector(".profile-menu-name").textContent, user.nickname);
  } finally { await view.close(); }
});

test("missing or broken Discord avatars fall back to the GuildUp avatar", async () => {
  for (const avatarUrl of [null, profileData.avatarUrl]) {
    const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
      if (url === "/api/auth/account") return response(profileAccount);
      if (url === "/api/account/profile") return response({ ...profileData, avatarUrl });
      return null;
    } });
    try {
      if (avatarUrl) await act(async () => document.querySelector(".account-profile-image img").dispatchEvent(new window.Event("error")));
      assert.equal(document.querySelector(".account-profile-image .account-avatar").tagName, "SPAN");
      assert.equal(document.querySelector(".account-profile-image .account-avatar").textContent, "G");
    } finally { await view.close(); }
  }
});

test("Discord-only users see a disabled disconnect action and explanation", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response({ ...profileAccount, email: null, canDisconnectDiscord: false });
    return null;
  } });
  try {
    const button = [...document.querySelectorAll("button")].find((item) => item.textContent === "연결 해제");
    assert.equal(button.disabled, true);
    await act(async () => button.click());
    assert.equal(document.querySelector('[aria-label="Discord 연결 해제 확인"]'), null);
    assert.match(document.body.textContent, /먼저 이메일 로그인을 추가해야/);
    assert.equal(view.requests.some(({ options }) => options.method === "DELETE"), false);
    assert.equal(document.querySelector(".account-email"), null);
  } finally { await view.close(); }
});

test("Discord disconnect requires explicit confirmation, uses CSRF, and preserves profile drafts", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/profile") return response(profileData);
    if (url === "/api/account/connections/discord") return new Response(null, { status: 204 });
    return null;
  } });
  try {
    await fill("profileNickname", "저장 전 닉네임");
    await clickText("연결 해제");
    assert.equal(view.requests.some(({ options }) => options.method === "DELETE"), false);
    assert.match(document.querySelector('[aria-label="Discord 연결 해제 확인"]').textContent, /private@example.com/);
    await clickText("Discord 연결 해제");
    const request = view.requests.find(({ options }) => options.method === "DELETE");
    assert.equal(request.url, "/api/account/connections/discord");
    assert.ok(new Headers(request.options.headers).get("X-CSRF-Token"));
    assert.equal(document.querySelector(".account-profile-image .account-avatar").tagName, "SPAN");
    assert.equal(document.querySelector('[name="profileNickname"]').value, "저장 전 닉네임");
    assert.ok([...document.querySelectorAll("button")].find((item) => item.textContent === "Discord 연결"));
    assert.equal(document.querySelector(".account-email").textContent, "private@example.com");
  } finally { await view.close(); }
});

test("server-side last-login-method rejection is shown without removing Discord", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: (url) => {
    if (url === "/api/auth/account") return response(profileAccount);
    if (url === "/api/account/connections/discord") return response({ code: "LAST_LOGIN_METHOD", message: "먼저 이메일 로그인을 추가해 주세요." }, 409);
    return null;
  } });
  try {
    await clickText("연결 해제"); await clickText("Discord 연결 해제");
    assert.match(document.querySelector('[role="alert"]').textContent, /이메일 로그인을 추가/);
    assert.ok(document.querySelector(".account-discord-identity"));
    assert.equal([...document.querySelectorAll("button")].some((item) => item.textContent === "Discord 연결"), false);
  } finally { await view.close(); }
});

const verificationStatus = { hasEmailCredential: true, emailVerified: false, deliveryStatus: "SENT", retryAfterSeconds: 0 };
for (const [deliveryStatus, text] of [["SENT", "메일함과 스팸함"], ["QUEUED", "발송 중"], ["FAILED", "가입 정보는 저장"]]) {
  test(`verification waiting screen explains ${deliveryStatus} and links account/login`, async () => {
    clearVerificationLinkToken();
    const view = await mount({ Component: EmailVerificationPage, path: "/email-verification.html", handle: url =>
      url === "/api/auth/email-verification" ? response({ ...verificationStatus, deliveryStatus }) : null });
    try {
      assert.match(document.body.textContent, /이메일 인증이 필요합니다/);
      assert.ok(document.body.textContent.includes(text));
      assert.ok(document.querySelector('a[href="/account.html"]'));
      assert.equal(view.requests.some(({ url }) => url.endsWith("/confirm")), false);
    } finally { await view.close(); }
  });
}

test("verification link clears browser address and requires explicit CSRF protected confirmation", async () => {
  clearVerificationLinkToken(); const raw = "A".repeat(43);
  const view = await mount({ Component: EmailVerificationPage, path: `/email-verification.html#token=${raw}`, handle: url => {
    if (url === "/api/auth/email-verification") return response(verificationStatus);
    if (url.endsWith("/confirm")) return new Response(null, { status: 204 });
    return null;
  } });
  try {
    assert.equal(window.location.hash, "");
    assert.equal(document.body.textContent.includes(raw), false);
    assert.equal(view.requests.some(({ url }) => url.endsWith("/confirm")), false);
    await clickText("이메일 인증 완료");
    const confirmation = view.requests.find(({ url }) => url.endsWith("/confirm"));
    assert.deepEqual(JSON.parse(confirmation.options.body), { token: raw });
    assert.equal(confirmation.options.headers.get("X-CSRF-Token"), "anonymous-or-current-session-token");
    assert.match(document.body.textContent, /이메일 인증이 완료되었습니다/);
    assert.match(document.body.textContent, /이제 GuildUp을 이용할 수 있습니다/);
    assert.equal(view.requests.some(({ url }) => url.includes(raw)), false);
  } finally { await view.close(); }
});

for (const [code, message] of [["EMAIL_VERIFICATION_EXPIRED", "인증 링크가 만료되었습니다. 인증 메일을 다시 요청해 주세요."],
  ["EMAIL_VERIFICATION_USED", "이미 사용한 인증 링크입니다. 계정 설정에서 인증 상태를 확인해 주세요."],
  ["EMAIL_VERIFICATION_INVALID", "인증 링크를 확인할 수 없습니다. 최근 메일을 다시 열어 주세요."]]) {
  test(`verification failure ${code} offers recovery`, async () => {
    clearVerificationLinkToken();
    const view = await mount({ Component: EmailVerificationPage, path: `/email-verification.html#token=${"B".repeat(43)}`, handle: url => {
      if (url === "/api/auth/email-verification") return response(verificationStatus);
      if (url.endsWith("/confirm")) return response({ code, message }, 400);
      return null;
    } });
    try {
      await clickText("이메일 인증 완료"); assert.equal(document.querySelector('[role="alert"]').textContent, message);
      assert.ok([...document.querySelectorAll("button")].find(button => button.textContent === "인증 메일 다시 보내기"));
    } finally { await view.close(); }
  });
}

test("verification resend prevents consecutive clicks and reflects server cooldown", async () => {
  clearVerificationLinkToken(); let resolveSend;
  const view = await mount({ Component: EmailVerificationPage, path: "/email-verification.html", handle: url => {
    if (url === "/api/auth/email-verification") return response(verificationStatus);
    if (url.endsWith("/resend")) return new Promise(resolve => { resolveSend = resolve; });
    return null;
  } });
  try {
    const button = [...document.querySelectorAll("button")].find(button => button.textContent === "인증 메일 다시 보내기");
    await act(async () => { button.click(); button.click(); });
    assert.equal(view.requests.filter(({ url }) => url.endsWith("/resend")).length, 1);
    assert.ok(button.disabled);
    await act(async () => resolveSend(response({ ...verificationStatus, retryAfterSeconds: 60 })));
    assert.ok(button.disabled); assert.match(button.textContent, /60초/);
  } finally { await view.close(); }
});

test("expired session guides login then reopening the original link without leaking token", async () => {
  clearVerificationLinkToken();
  const view = await mount({ Component: EmailVerificationPage, path: `/email-verification.html#token=${"C".repeat(43)}`, handle: url =>
    url === "/api/auth/email-verification" ? response({ message: "로그인이 필요합니다." }, 401) : null });
  try {
    assert.match(document.body.textContent, /로그인한 뒤 메일의 인증 링크를 다시/);
    assert.equal(document.querySelectorAll(".verification-card button").length, 0);
  } finally { await view.close(); }
});

test("Discord only account has no email verification warning or resend action", async () => {
  const view = await mount({ Component: AccountSettingsPage, path: "/account.html", handle: url =>
    url === "/api/auth/account" ? response({ user, email: null, discordConnected: true, memberLinkConflicts: [] }) : null });
  try {
    assert.equal(document.body.textContent.includes("인증 필요"), false);
    assert.equal(document.querySelector(".email-verification-panel"), null);
  } finally { await view.close(); }
});

test("forgot password page sends only email with CSRF and displays the same generic acknowledgement", async () => {
  const view = await mount({ Component: ForgotPasswordPage, path: "/forgot-password.html", handle: url =>
    url === "/api/auth/password-reset/request" ? response({ message: "accepted" }, 202) : null });
  try {
    assert.equal(document.querySelector("h1").textContent, "비밀번호 찾기");
    assert.match(document.body.textContent, /가입하신 이메일 주소를 입력하면/);
    assert.equal(document.querySelector('[name="email"]').type, "email");
    await fill("email", "missing@example.com"); await submit();
    const request = view.requests.find(({ url }) => url === "/api/auth/password-reset/request");
    assert.deepEqual(JSON.parse(request.options.body), { email: "missing@example.com" });
    assert.equal(request.options.headers.get("X-CSRF-Token"), "anonymous-or-current-session-token");
    assert.match(document.querySelector('[role="status"]').textContent, /재설정 안내를 보낼 수 있는 경우 잠시 후 이메일이 발송됩니다/);
    assert.equal(document.querySelector('[name="email"]'), null);
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("forgot password prevents duplicate submissions while loading and allows retry after network failure", async () => {
  let finish;
  const view = await mount({ Component: ForgotPasswordPage, path: "/forgot-password.html", handle: url =>
    url === "/api/auth/password-reset/request" ? new Promise(resolve => { finish = resolve; }) : null });
  try {
    await fill("email", "user@example.com");
    await act(async () => {
      const form = document.querySelector("form");
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
    });
    assert.equal(view.requests.filter(({ url }) => url === "/api/auth/password-reset/request").length, 1);
    assert.equal(document.querySelector(".auth-submit").disabled, true);
    assert.equal(document.querySelector(".auth-submit").textContent, "요청 중...");
    await act(async () => finish(response({ message: "서버 처리 중 오류가 발생했습니다." }, 503)));
    assert.match(document.querySelector('[role="alert"]').textContent, /오류/);
    assert.equal(document.querySelector(".auth-submit").disabled, false);
  } finally { await view.close(); }
});

test("reset link is removed before requests and validation alone never consumes it", async () => {
  clearResetLinkToken(); const raw = "R".repeat(43);
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${raw}`, handle: url =>
    url === "/api/auth/password-reset/validate" ? new Response(null, { status: 204 }) : null });
  try {
    assert.equal(window.location.hash, "");
    assert.equal(document.querySelector("h1").textContent, "새 비밀번호 설정");
    assert.equal(view.requests.some(({ url }) => url.endsWith("/confirm")), false);
    assert.equal(document.body.textContent.includes(raw), false);
    assert.equal(view.requests.some(({ url }) => url.includes(raw)), false);
    const validation = view.requests.find(({ url }) => url.endsWith("/validate"));
    assert.deepEqual(JSON.parse(validation.options.body), { token: raw });
    assert.ok(validation.options.headers.get("X-CSRF-Token"));
    assert.equal(window.localStorage.length, 0); assert.equal(window.sessionStorage.length, 0);
    assert.equal(document.querySelector('[name="password"]').autocomplete, "new-password");
  } finally { await view.close(); }
});

test("reset page toggles visibility, validates password policy and confirms an exact match", async () => {
  clearResetLinkToken();
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${"S".repeat(43)}`, handle: url =>
    url.endsWith("/validate") ? new Response(null, { status: 204 }) : null });
  try {
    await clickText("비밀번호 표시");
    assert.equal(document.querySelector('[name="password"]').type, "text");
    assert.equal(document.querySelector('[name="passwordConfirmation"]').type, "text");
    assert.equal(document.querySelector(".password-visibility").getAttribute("aria-pressed"), "true");
    await clickText("비밀번호 숨기기"); assert.equal(document.querySelector('[name="password"]').type, "password");
    await fill("password", "short1"); await fill("passwordConfirmation", "short1"); await submit();
    assert.match(document.querySelector('[role="alert"]').textContent, /8자 이상/);
    await fill("password", "GuildUp456!"); await fill("passwordConfirmation", "Other123!"); await submit();
    assert.match(document.querySelector('[role="alert"]').textContent, /일치하지 않습니다/);
    assert.equal(view.requests.some(({ url }) => url.endsWith("/confirm")), false);
    await fill("passwordConfirmation", "GuildUp456!");
    assert.equal(document.querySelector("#reset-password-match").textContent, "비밀번호가 일치합니다.");
  } finally { await view.close(); }
});

test("reset success clears passwords, shows re-login action and never logs in automatically", async () => {
  clearResetLinkToken(); const raw = "T".repeat(43);
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${raw}`, handle: url =>
    url.endsWith("/validate") || url.endsWith("/confirm") ? new Response(null, { status: 204 }) : null });
  try {
    await fill("password", "NewPass123!"); await fill("passwordConfirmation", "NewPass123!"); await submit();
    const confirmation = view.requests.find(({ url }) => url.endsWith("/confirm"));
    assert.deepEqual(JSON.parse(confirmation.options.body), { token: raw, password: "NewPass123!", passwordConfirmation: "NewPass123!" });
    assert.ok(confirmation.options.headers.get("X-CSRF-Token"));
    assert.equal(document.querySelector('[role="status"]').textContent, "비밀번호가 변경되었습니다.새로운 비밀번호로 로그인해 주세요.");
    assert.equal(document.querySelector('.reset-card a[href="/login.html"]').textContent, "로그인하러 가기");
    assert.equal(document.querySelectorAll("input").length, 0);
    assert.equal(view.requests.some(({ url }) => url === "/api/auth/login"), false);
    assert.deepEqual(view.redirects, []);
  } finally { await view.close(); }
});

test("reset confirm prevents consecutive submissions and shows loading", async () => {
  clearResetLinkToken(); let finish;
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${"U".repeat(43)}`, handle: url => {
    if (url.endsWith("/validate")) return new Response(null, { status: 204 });
    if (url.endsWith("/confirm")) return new Promise(resolve => { finish = resolve; });
    return null;
  } });
  try {
    await fill("password", "NewPass123!"); await fill("passwordConfirmation", "NewPass123!");
    await act(async () => {
      const form = document.querySelector("form");
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
      form.dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
    });
    assert.equal(view.requests.filter(({ url }) => url.endsWith("/confirm")).length, 1);
    assert.equal(document.querySelector(".auth-submit").textContent, "변경 중...");
    assert.ok(document.querySelector(".auth-submit").disabled);
    await act(async () => finish(new Response(null, { status: 204 })));
    assert.match(document.body.textContent, /비밀번호가 변경되었습니다/);
  } finally { await view.close(); }
});

for (const [code, message] of [["PASSWORD_RESET_EXPIRED", "재설정 링크가 만료되었습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요."],
  ["PASSWORD_RESET_INVALID", "재설정 링크를 사용할 수 없습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요."],
  ["PASSWORD_RESET_REUSED", "재설정 링크를 사용할 수 없습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요."]]) {
  test(`reset invalid link ${code} hides password inputs and provides recovery`, async () => {
    clearResetLinkToken();
    const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${"V".repeat(43)}`, handle: url =>
      url.endsWith("/validate") ? response({ code, message }, 400) : null });
    try {
      assert.equal(document.querySelector('[role="alert"]').textContent, message);
      assert.equal(document.querySelectorAll("input").length, 0);
      assert.ok(document.querySelector('a[href="/forgot-password.html"]'));
      assert.equal(view.requests.some(({ url }) => url.endsWith("/confirm")), false);
    } finally { await view.close(); }
  });
}

test("token that expires during editing cannot submit and preserves the expiration explanation", async () => {
  clearResetLinkToken();
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${"W".repeat(43)}`, handle: url => {
    if (url.endsWith("/validate")) return new Response(null, { status: 204 });
    if (url.endsWith("/confirm")) return response({ code: "PASSWORD_RESET_EXPIRED", message: "재설정 링크가 만료되었습니다." }, 400);
    return null;
  } });
  try {
    await fill("password", "NewPass123!"); await fill("passwordConfirmation", "NewPass123!"); await submit();
    assert.equal(document.querySelector('[role="alert"]').textContent, "재설정 링크가 만료되었습니다.");
    assert.equal(document.querySelectorAll("input").length, 0);
    assert.ok(document.querySelector('a[href="/forgot-password.html"]'));
  } finally { await view.close(); }
});

test("missing reset token shows recovery without sending validation or mutation", async () => {
  clearResetLinkToken();
  const view = await mount({ Component: PasswordResetPage, path: "/password-reset.html" });
  try {
    assert.match(document.querySelector('[role="alert"]').textContent, /새 링크를 요청/);
    assert.equal(view.requests.some(({ url }) => url.includes("/password-reset/")), false);
  } finally { await view.close(); }
});

test("reset HTML removes fragment before bundle loading and uses no-referrer with responsive viewport", async () => {
  const html = await readFile(resolve("password-reset.html"), "utf8");
  assert.match(html, /name="referrer" content="no-referrer"/);
  assert.match(html, /name="viewport" content="width=device-width, initial-scale=1.0"/);
  assert.ok(html.indexOf("history.replaceState") < html.indexOf('src="/src/main.jsx"'));
  const css = await readFile(resolve("src/styles/onboarding.css"), "utf8");
  assert.match(css, /@media \(max-width: 480px\)[\s\S]*\.reset-card/);
});

test("opening another reset link in the same browser tab validates it and clears the previous success", async () => {
  clearResetLinkToken();
  const view = await mount({ Component: PasswordResetPage, path: `/password-reset.html#token=${"X".repeat(43)}`, handle: url => {
    if (url.endsWith("/validate") || url.endsWith("/confirm")) return new Response(null, { status: 204 });
    return null;
  } });
  try {
    await fill("password", "NewPass123!"); await fill("passwordConfirmation", "NewPass123!"); await submit();
    assert.match(document.body.textContent, /비밀번호가 변경되었습니다/);
    await act(async () => {
      window.history.replaceState(null, "", `/password-reset.html#token=${"Y".repeat(43)}`);
      window.dispatchEvent(new window.Event("hashchange"));
    });
    assert.equal(window.location.hash, "");
    assert.equal(document.querySelector('[name="password"]').value, "");
    const validation = view.requests.filter(({ url }) => url.endsWith("/validate")).at(-1);
    assert.deepEqual(JSON.parse(validation.options.body), { token: "Y".repeat(43) });
    assert.equal(document.querySelector(".auth-success"), null);
  } finally { await view.close(); }
});
