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

const temp = await mkdtemp(resolve("node_modules/.community-creation-"));
const output = resolve(temp, "app.mjs");
await build({ entryPoints: ["src/App.jsx"], outfile: output, bundle: true, platform: "node", format: "esm",
  jsx: "automatic", packages: "external" });
const { default: App } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));
const storageKey = "guildup.community-creation.1";
const draft = (extra = {}) => ({ name: "치즈 클랜", gameType: "BATTLEGROUNDS_KAKAO", step: 1,
  discord: null, connecting: false, requestId: "12345678-1234-1234-1234-123456789012", pending: false, ...extra });
const response = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});

async function mount(path = "/community-create.html", savedDraft = draft(), handle = () => response([])) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${path}` });
  if (savedDraft) dom.window.sessionStorage.setItem(storageKey, JSON.stringify(savedDraft));
  dom.window.open = () => ({ close() {}, location: {} });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    sessionStorage: dom.window.sessionStorage, HTMLElement: dom.window.HTMLElement,
    Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    if (url === "/api/auth/me") return response({ id: 1, nickname: "운영자" });
    if (url === "/api/auth/csrf") return response({ token: "csrf-token" });
    if (url.endsWith("/news-summary")) return response({ notices: [], events: [] });
    if (url === "/api/communities/9") return response({ id: 9, name: "치즈 클랜", role: "OWNER", discordConnected: false, games: [] });
    return handle(url, options);
  };
  const root = createRoot(document.getElementById("root"));
  await act(async () => root.render(React.createElement(BrowserRouter, null, React.createElement(App))));
  return { requests, created: () => requests.filter((r) => r.url === "/api/communities" && r.options?.method === "POST"),
    async close() {
      await act(async () => root.unmount());
      globalThis.fetch = previousFetch;
      for (const [key, descriptor] of saved) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
      }
      dom.window.close();
    },
  };
}
async function click(label) {
  const button = [...document.querySelectorAll("button")].find((item) => item.textContent === label);
  assert.ok(button, `button ${label} exists`);
  await act(async () => button.click());
}

function discordResult() {
  return { user: { username: "operator", globalName: "운영자" }, guilds: [{ id: "100", name: "CHEEZE SERVER", owner: true }] };
}

test("step changes, going back and skipping Discord never create a community; only the final button does", async () => {
  const page = await mount(undefined, draft(), (url) => response(url === "/api/communities" ? { id: 9 } : []));
  try {
    assert.ok(document.querySelector("#community-name"));
    await click("계속하기 →"); await click("← 이전");
    assert.equal(page.created().length, 0);
    await click("계속하기 →"); await click("나중에 연결하기");
    assert.equal(document.activeElement.id, "creation-title");
    assert.match(document.body.textContent, /Discord · 나중에 연결/);
    assert.equal(page.created().length, 0);
    await click("커뮤니티 만들기");
    assert.equal(page.created().length, 1);
    assert.deepEqual(JSON.parse(page.created()[0].options.body), {
      name: "치즈 클랜", gameType: "BATTLEGROUNDS_KAKAO", discordInstallToken: null,
    });
    assert.ok(new Headers(page.created()[0].options.headers).get("Idempotency-Key"));
    assert.equal(sessionStorage.getItem(storageKey), null);
  } finally { await page.close(); }
});

test("OAuth cancellation preserves the input and creates no community", async () => {
  const page = await mount("/community-create.html?oauthError=discord", draft({ step: 2 }));
  try {
    assert.match(document.body.textContent, /Discord 인증이 취소/);
    await click("나중에 연결하기");
    assert.match(document.body.textContent, /치즈 클랜/);
    assert.equal(page.created().length, 0);
  } finally { await page.close(); }
});

test("successful Discord preparation reaches preview before the final request creates the connected community", async () => {
  const page = await mount("/community-create.html?oauthResult=result", draft({ step: 2 }), (url) => {
    if (url.includes("/oauth/results/")) return response(discordResult());
    if (url.endsWith("/bot-install/authorize")) return response({ alreadyInstalled: true, installToken: "verified-token", guildName: "CHEEZE SERVER" });
    if (url === "/api/communities") return response({ id: 9 });
    return response([]);
  });
  try {
    await click("선택"); await click("GuildUp 봇 추가하기");
    assert.match(document.querySelector(".creation-preview").textContent, /CHEEZE SERVER/);
    assert.equal(page.created().length, 0);
    assert.equal(JSON.parse(sessionStorage.getItem(storageKey)).discord.installToken, "verified-token");
    await click("커뮤니티 만들기");
    assert.equal(page.created().length, 1);
    assert.deepEqual(JSON.parse(page.created()[0].options.body), {
      name: "치즈 클랜", gameType: "BATTLEGROUNDS_KAKAO", discordInstallToken: "verified-token",
    });
  } finally { await page.close(); }
});

test("bot installation failure and duplicate guild never advance to final creation", async () => {
  for (const duplicate of [false, true]) {
    const page = await mount("/community-create.html?oauthResult=result", draft({ step: 2 }), (url) => {
      if (url.includes("/oauth/results/")) return response(discordResult());
      if (url.endsWith("/bot-install/authorize")) return duplicate
        ? response({ code: "DISCORD_GUILD_ALREADY_CONNECTED", communityName: "기존 클랜", message: "이미 등록됨" }, 409)
        : response({ alreadyInstalled: false, installToken: "install", authorizationUrl: "https://discord.com/oauth2/authorize" });
      if (url.endsWith("/bot-install/confirm")) return response({ message: "봇 미설치" }, 409);
      return response([]);
    });
    try {
      await click("선택"); await click("GuildUp 봇 추가하기");
      if (!duplicate) await click("설치 확인");
      assert.equal(document.querySelector(".creation-preview"), null);
      assert.equal(page.created().length, 0);
      if (duplicate) assert.match(document.body.textContent, /기존 클랜.*커뮤니티에 연결/);
    } finally { await page.close(); }
  }
});

test("double click sends one final request and a network retry uses the same persisted key", async () => {
  let release;
  let attempt = 0;
  const page = await mount(undefined, draft({ step: 3 }), (url) => {
    if (url !== "/api/communities") return response([]);
    attempt++;
    if (attempt === 1) return new Promise((resolve, reject) => { release = () => reject(new Error("response lost")); });
    return response({ id: 9 });
  });
  try {
    const button = [...document.querySelectorAll("button")].find((item) => item.textContent === "커뮤니티 만들기");
    await act(async () => { button.click(); button.click(); });
    assert.equal(page.created().length, 1);
    await act(async () => release());
    assert.equal(JSON.parse(sessionStorage.getItem(storageKey)).pending, true);
    assert.equal([...document.querySelectorAll("button")].find((item) => item.textContent === "← 이전").disabled, true);
    await click("커뮤니티 만들기");
    assert.equal(page.created().length, 2);
    assert.equal(new Headers(page.created()[0].options.headers).get("Idempotency-Key"),
      new Headers(page.created()[1].options.headers).get("Idempotency-Key"));
    assert.equal(page.created()[0].options.body, page.created()[1].options.body);
  } finally { await page.close(); }
});

test("community list leads with memberships, shows member count and separates invitation and creation", async () => {
  const page = await mount("/communities.html", null, (url) => response(url === "/api/auth/me/communities"
    ? [{ id: 9, name: "치즈 클랜", gameName: "PUBG", memberCount: 83, role: "MEMBER" }] : []));
  try {
    assert.match(document.querySelector(".community-grid").textContent, /PUBG · 83명.*입장/s);
    assert.equal(document.querySelector(".create-community-card"), null);
    const aside = document.querySelector(".community-secondary-actions");
    assert.match(aside.textContent, /초대 코드로 참여하기/);
    assert.equal(document.querySelector('.community-grid a[href="/community-create.html"]').textContent, "+ 새 커뮤니티 만들기");
    assert.equal(document.querySelector('.community-card[href="/community-dashboard.html?communityId=9"]').getAttribute("aria-label"), "치즈 클랜 커뮤니티 입장");
    await click("초대 코드로 참여하기");
    assert.ok(document.querySelector("#invite-code"));
    assert.equal(page.created().length, 0);
  } finally { await page.close(); }
});

test("an already connected Discord guild is blocked immediately on selection", async () => {
  const page = await mount("/community-create.html?oauthResult=result", draft({ step: 2 }), (url) => {
    if (url.includes("/oauth/results/")) return response(discordResult());
    if (url.endsWith("/guild-selection/inspect")) return response({ alreadyConnected: true, communityName: "기존 클랜" });
    throw new Error(`unexpected request ${url}`);
  });
  try {
    await click("선택");
    assert.match(document.body.textContent, /기존 클랜.*커뮤니티에 연결/);
    assert.equal([...document.querySelectorAll("button")].some((button) => button.textContent === "GuildUp 봇 추가하기"), false);
    assert.equal(page.created().length, 0);
  } finally { await page.close(); }
});

test("leaving and returning after Discord verification restores only a browser draft", async () => {
  const saved = draft({ step: 3, discord: { installToken: "verified-token", guildName: "CHEEZE SERVER" } });
  const page = await mount(undefined, saved);
  try {
    assert.match(document.querySelector(".creation-preview").textContent, /치즈 클랜.*CHEEZE SERVER/s);
    assert.equal(page.created().length, 0);
    assert.equal(page.requests.some((r) => r.url.includes("bot-install")), false);
  } finally { await page.close(); }
});

test("creation errors appear inline and preserve the preview, input and request key", async () => {
  const original = draft({ step: 3 });
  const page = await mount(undefined, original, (url) => response(url === "/api/communities"
    ? { message: "커뮤니티 이름을 다시 확인해주세요." } : [], url === "/api/communities" ? 400 : 200));
  try {
    await click("커뮤니티 만들기");
    assert.match(document.querySelector('[role="alert"]').textContent, /커뮤니티 이름을 다시 확인/);
    assert.match(document.querySelector(".creation-preview").textContent, /치즈 클랜/);
    const saved = JSON.parse(sessionStorage.getItem(storageKey));
    assert.equal(saved.name, original.name);
    assert.equal(saved.requestId, original.requestId);
    await click("← 이전"); await click("← 이전");
    assert.equal(document.querySelector("#community-name").value, original.name);
  } finally { await page.close(); }
});

test("the OAuth choice uses the existing authorize URL with inline loading and no community request", async () => {
  const page = await mount(undefined, draft({ step: 2 }));
  try {
    const link = document.querySelector('.onboarding-choice a[href="/api/community-creation/discord/oauth/authorize"]');
    assert.ok(link);
    assert.match(document.querySelector(".is-later").textContent, /Discord 없이도.*언제든 연결/s);
    // Prevent JSDOM navigation after React handles the real anchor click.
    document.addEventListener("click", (event) => event.preventDefault(), { once: true });
    await act(async () => link.click());
    assert.equal(link.textContent, "Discord로 이동 중...");
    assert.equal(link.getAttribute("aria-disabled"), "true");
    assert.equal([...document.querySelectorAll("button")].find((item) => item.textContent === "나중에 연결하기").disabled, true);
    assert.equal(page.created().length, 0);
    await act(async () => window.dispatchEvent(new Event("pageshow")));
    assert.equal(link.textContent, "Discord 연결하기 →");
    assert.equal(link.getAttribute("aria-disabled"), "false");
  } finally { await page.close(); }
});

test("existing communities retain the shared post-creation Discord connection flow and API", async () => {
  const page = await mount("/discord-connect.html?communityId=9&oauthResult=result", null, (url) => {
    if (url.includes("/oauth/results/")) return response(discordResult());
    if (url.endsWith("/bot-install/authorize")) return response({ alreadyInstalled: true });
    return response([]);
  });
  try {
    assert.ok(document.querySelector(".connect-panel"));
    assert.equal(document.querySelector(".onboarding-choices"), null);
    await click("선택"); await click("GuildUp 봇 추가하기");
    assert.equal(page.created().length, 0);
    assert.ok(page.requests.some((request) => request.url === "/api/communities/9/discord/bot-install/authorize"));
    assert.equal(page.requests.some((request) => request.url.startsWith("/api/community-creation/")), false);
    assert.equal(window.location.pathname, "/community-dashboard.html");
  } finally { await page.close(); }
});

test("a member of a disconnected community can register a game account on the dashboard", async () => {
  const endpoint = "/api/communities/901/games/11/pubg-account/me";
  const page = await mount("/community-dashboard.html?communityId=901", null, (url) => {
    if (url === "/api/communities/901") return response({ id: 901, name: "독립 클랜", role: "MEMBER", discordConnected: false,
      games: [{ communityGameId: 11, gameType: "BATTLEGROUNDS_KAKAO", gameName: "PUBG Kakao", capabilities: ["BINGO", "KILL_COMPETITION", "NICKNAME_SYNC"] }] });
    if (url === endpoint) return response({ nickname: "CheezePlayer", accountId: "account-native" });
    return response([]);
  });
  try {
    assert.equal(document.querySelector("#my-game-nickname").value, "CheezePlayer");
    await click("게임 계정 연결하기");
    const registration = page.requests.find((request) => request.url === endpoint && request.options?.method === "PUT");
    assert.ok(registration);
    assert.deepEqual(JSON.parse(registration.options.body), { nickname: "CheezePlayer" });
    assert.match(document.body.textContent, /게임 계정을 연결했습니다/);
  } finally { await page.close(); }
});

test("Discord-specific pages show connection guidance without requesting Discord APIs", async () => {
  const page = await mount("/discord-dm.html?communityId=902", null, (url) => {
    if (url === "/api/communities/902") return response({ id: 902, name: "독립 클랜", role: "OWNER", discordConnected: false, games: [] });
    return response([]);
  });
  try {
    assert.match(document.body.textContent, /Discord가 연결되지 않았습니다/);
    assert.ok(document.querySelector('a[href="/discord-connect.html?communityId=902"]'));
    assert.equal(page.requests.some((request) => request.url.includes("/discord/dm") || request.url.includes("/discord/roles")), false);
  } finally { await page.close(); }
});
