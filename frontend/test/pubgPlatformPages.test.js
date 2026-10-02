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

// Bundle the real application, keeping React shared with the test renderer.
const temp = await mkdtemp(resolve("node_modules/.platform-pages-"));
const output = resolve(temp, "app.mjs");
await build({ entryPoints: ["src/App.jsx"], outfile: output, bundle: true, platform: "node", format: "esm",
  jsx: "automatic", packages: "external" });
const { default: App } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));

const community = (id) => ({ id, name: "플랫폼 테스트", role: "OWNER", discordConnected: id === 107, games: [
  { communityGameId: 10, gameType: "BATTLEGROUNDS_KAKAO", gameName: "PUBG Kakao", capabilities: ["BINGO", "KILL_COMPETITION", "TEAM_MAKER", "ACTIVITY", "NICKNAME_SYNC"] },
  { communityGameId: 20, gameType: "BATTLEGROUNDS_STEAM", gameName: "PUBG Steam", capabilities: ["BINGO", "KILL_COMPETITION", "TEAM_MAKER", "ACTIVITY", "NICKNAME_SYNC"] },
] });
const response = (body) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });

async function mount(path, id, handle, strict = false) {
  const dom = new JSDOM("<div id='root'></div>", { url: `http://localhost${path}?communityId=${id}&communityGameId=10${path === "/bingos.html" ? "&view=manage" : ""}` });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    if (url === `/api/communities/${id}`) return response(community(id));
    if (url === "/api/auth/me") return response({ nickname: "테스트" });
    return handle(url, options);
  };
  const root = createRoot(document.getElementById("root"));
  const app = React.createElement(BrowserRouter, null, React.createElement(App));
  await act(async () => root.render(strict ? React.createElement(React.StrictMode, null, app) : app));
  return { requests,
    async switchTo(gameId) {
      const select = document.querySelector('select[aria-label="PUBG 플랫폼"]');
      assert.ok(select);
      await act(async () => { select.value = String(gameId); select.dispatchEvent(new Event("change", { bubbles: true })); });
    },
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
async function click(text) {
  const button = [...document.querySelectorAll("button")].find((item) => item.textContent === text);
  assert.ok(button, `button ${text} exists`);
  await act(async () => button.click());
}

test("StrictMode request cancellation never shows an abort error on the current Team Maker page", async () => {
  let aborted = 0;
  const page = await mount("/team-maker.html", 108, async (_url, { signal }) => new Promise((done, reject) => {
    signal.addEventListener("abort", () => {
      aborted++;
      reject(new DOMException("signal is aborted without reason", "AbortError"));
    }, { once: true });
    queueMicrotask(() => {
      if (!signal.aborted) done(response({ participants: [{ memberId: 1, discordNickname: "LoadedMember", pubgNickname: "Player" }] }));
    });
  }), true);
  try {
    assert.ok(aborted > 0, "real effect cleanup cancels the first request");
    assert.match(document.body.textContent, /LoadedMember/);
    assert.equal(document.querySelector('[role="alert"]'), null);
  } finally { await page.close(); }
});

for (const [path, id, text] of [["/bingos.html", 109, "빙고 관리"], ["/kill-competitions.html", 110, "킬내기"]]) {
  test(`StrictMode cancellation never displays an error or fallback warning on ${path}`, async () => {
    let aborted = 0;
    const page = await mount(path, id, async (url, { signal }) => new Promise((done, reject) => {
      signal.addEventListener("abort", () => {
        aborted++;
        reject(new DOMException("signal is aborted without reason", "AbortError"));
      }, { once: true });
      queueMicrotask(() => {
        if (!signal.aborted) done(response(url.endsWith("/catalog") ? { items: [], weapons: [], maps: {} } : []));
      });
    }), true);
    try {
      assert.ok(aborted > 0);
      assert.match(document.querySelector("h1").textContent, new RegExp(text));
      assert.equal(document.querySelector('[role="alert"]'), null);
      assert.equal(document.querySelector('[role="status"]'), null);
      assert.doesNotMatch(document.body.textContent, /signal is aborted|미션 선택 정보를 불러오지 못했습니다/);
    } finally { await page.close(); }
  });
}

test("real server errors are still displayed on Team Maker", async () => {
  const page = await mount("/team-maker.html", 111, async () => new Response("{}", { status: 503 }));
  try {
    assert.match(document.querySelector('[role="alert"]').textContent, /서버 처리 중 오류/);
  } finally { await page.close(); }
});

test("Bingo editor is reset for Kakao → Steam and Steam → Kakao", async () => {
  const page = await mount("/bingos.html", 101, async (url) => response(url.endsWith("/catalog") ? { items: [], maps: {}, weapons: [] } : []));
  try {
    for (const next of [20, 10]) {
      await click("빙고 생성");
      assert.ok(document.querySelector("form.bingo-editor"));
      await page.switchTo(next);
      assert.equal(document.querySelector("form.bingo-editor"), null);
      assert.match(document.querySelector(".pubg-platform-bar").textContent, next === 20 ? /PUBG · Steam/ : /PUBG · Kakao/);
    }
  } finally { await page.close(); }
});

test("Team Maker result, manual damage, selected participants and options reset on platform switch", async () => {
  const page = await mount("/team-maker.html", 102, async (url) => {
    if (url.endsWith("/participants")) return response({ participants: [{ memberId: 1, discordNickname: url.includes("/games/10/") ? "KakaoMember" : "SteamMember", pubgNickname: "Player" }] });
    return response({ teams: [], participants: [], missingStatsParticipants: [{ memberId: 1, discordNickname: "KakaoMissing", pubgNickname: "KakaoNick" }] });
  });
  try {
    await click("팀 생성");
    const manual = document.querySelector(".manual-damage-input input");
    assert.ok(manual);
    await act(async () => { manual.value = "123"; manual.dispatchEvent(new Event("input", { bubbles: true })); });
    await page.switchTo(20);
    assert.equal(document.querySelector(".manual-damage-input"), null);
    assert.equal(document.querySelector(".team-results-panel"), null);
    assert.match(document.body.textContent, /SteamMember/);
    assert.doesNotMatch(document.body.textContent, /KakaoMember|KakaoMissing/);
  } finally { await page.close(); }
});

test("a late Kakao Team Maker HTTP result cannot overwrite the Steam screen", async () => {
  let release;
  const page = await mount("/team-maker.html", 103, async (url) => {
    if (url.endsWith("/participants")) return response({ participants: [{ memberId: 1, discordNickname: url.includes("/games/10/") ? "KakaoMember" : "SteamMember", pubgNickname: "Player" }] });
    return new Promise((done) => { release = () => done(response({ teams: [], participants: [], missingStatsParticipants: [{ memberId: 1, discordNickname: "LateKakaoResult", pubgNickname: "Late" }] })); });
  });
  try {
    await click("팀 생성");
    const oldRequest = page.requests.find((item) => item.url.endsWith("/generate"));
    await page.switchTo(20);
    assert.equal(oldRequest.options.signal.aborted, true);
    await act(async () => release()); // Mock fetch deliberately ignores cancellation.
    assert.match(document.body.textContent, /SteamMember/);
    assert.doesNotMatch(document.body.textContent, /LateKakaoResult/);
  } finally { await page.close(); }
});

test("Kill Competition creation state does not carry over to the other platform", async () => {
  const page = await mount("/kill-competitions.html", 104, async () => response([]));
  try {
    await click("킬내기 만들기");
    assert.ok(document.querySelector(".kill-create-form"));
    document.querySelector('input[name="title"]').value = "Kakao only";
    await page.switchTo(20);
    assert.equal(document.querySelector(".kill-create-form"), null);
    assert.doesNotMatch(document.body.textContent, /Kakao only/);
  } finally { await page.close(); }
});

test("Members displays the selected platform account without the other platform nickname", async () => {
  const page = await mount("/members.html", 105, async (url) => response(url.endsWith("/members") ? [{
    id: 1, nickname: "DiscordMember", status: "ACTIVE", gameNickname: "LegacyKakao",
    pubgAccounts: [{ platform: "KAKAO", nickname: "KakaoNick", accountId: "k" }, { platform: "STEAM", nickname: "SteamNick", accountId: "s" }],
  }] : {}));
  try {
    assert.match(document.querySelector("tbody").textContent, /KakaoNick/);
    await page.switchTo(20);
    assert.match(document.querySelector("tbody").textContent, /SteamNick/);
    assert.doesNotMatch(document.querySelector("tbody").textContent, /KakaoNick|LegacyKakao/);
  } finally { await page.close(); }
});

test("Community settings loads rules and builds links for the selected platform", async () => {
  const page = await mount("/community-settings.html", 106, async (url) => response(url.endsWith("/users") ? []
    : url.endsWith("/ranking-settings") ? { periodType: "MONTHLY" }
    : { configured: true, activityPeriodDays: 14, minimumClanMembersInRoster: 2 }));
  try {
    await page.switchTo(20);
    assert.ok(page.requests.some((item) => item.url.includes("/games/20/activity-rule")));
    const nicknameLink = document.querySelector('a[href^="/game-nickname-settings.html"]');
    assert.match(nicknameLink.getAttribute("href"), /communityGameId=20/);
  } finally { await page.close(); }
});

test("Integrations activity links follow the selected PUBG platform", async () => {
  const page = await mount("/integrations.html", 107, async () => response({}));
  try {
    await page.switchTo(20);
    const links = [...document.querySelectorAll('a[href^="/member-activities.html"]')];
    assert.ok(links.some((link) => link.closest(".integration-service-list") && link.href.includes("communityGameId=20")));
  } finally { await page.close(); }
});
