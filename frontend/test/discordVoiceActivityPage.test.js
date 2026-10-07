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

const temp = await mkdtemp(resolve("node_modules/.voice-page-"));
const output = resolve(temp, "page.mjs");
await build({ entryPoints: ["src/pages/DiscordVoiceActivityPage.jsx"], outfile: output,
  bundle: true, platform: "node", format: "esm", jsx: "automatic", packages: "external",
  plugins: [{ name: "voice-page-layout", setup(builder) {
    builder.onLoad({ filter: /DashboardLayout\.jsx$/ }, () => ({ contents:
      "export default function Layout({ children }) { return children; }", loader: "js" }));
  } }],
});
const { default: Page } = await import(pathToFileURL(output));
after(() => rm(temp, { recursive: true, force: true }));

const endpoint = "/api/communities/9/discord/voice-activity";
const response = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
const member = (id = 1, seconds = 60) => ({ communityMemberId: id, nickname: `클랜원${id}`,
  discordUserId: `discord-${id}`, totalSeconds: seconds, currentlyConnected: false,
  lastJoinedAt: "2026-10-07T00:00:00Z" });
const detail = (id = 1, seconds = 60) => ({ ...member(id, seconds), sessions: [{
  channelId: "voice-1", channelName: `채널${id}`, joinedAt: "2026-10-06T14:00:00Z",
  leftAt: "2026-10-06T17:00:00Z", durationSeconds: seconds, currentlyConnected: false,
}] });

async function mount(handle, strict = false, search = "communityId=9") {
  const dom = new JSDOM("<div id='root'></div>", {
    url: `http://localhost/discord-voice-activity.html?${search}`,
  });
  const NativeDate = globalThis.Date;
  class FixedDate extends NativeDate {
    constructor(...args) { super(...(args.length ? args : ["2026-10-07T03:00:00Z"])); }
    static now() { return new NativeDate("2026-10-07T03:00:00Z").getTime(); }
  }
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    HTMLElement: dom.window.HTMLElement, Event: dom.window.Event, Date: FixedDate, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const previousFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => { requests.push({ url, options }); return handle(url, options); };
  const root = createRoot(document.getElementById("root"));
  const content = React.createElement(BrowserRouter, null, React.createElement(Page));
  await act(async () => root.render(strict ? React.createElement(React.StrictMode, null, content) : content));
  return { requests, async close() {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  } };
}

async function clickPeriod(label) {
  const button = [...document.querySelectorAll(".voice-period-selector button")].find((item) => item.textContent === label);
  assert.ok(button);
  await act(async () => button.click());
}
async function clickMember(index = 0) {
  await act(async () => document.querySelectorAll(".voice-member-button")[index].click());
}
const requestPeriod = (url) => new URL(url, "http://localhost").searchParams.get("period");
async function clickLabel(label) {
  const button = document.querySelector(`button[aria-label="${label}"]`);
  assert.ok(button, label);
  await act(async () => button.click());
}

test("전체가 기본이며 모든 기간 선택은 목록과 상세에 같은 period를 전달한다", async () => {
  const page = await mount((url) => {
    const period = new URL(url, "http://localhost").searchParams.get("period");
    const seconds = { ALL: 10800, DAY: 7200, WEEK: 3600, MONTH: 1800, YEAR: 600 }[period];
    return response(url.includes("/members/") ? detail(1, seconds) : [member(1, seconds)]);
  });
  try {
    assert.equal(page.requests[0].url, `${endpoint}?period=ALL`);
    assert.deepEqual([...document.querySelectorAll(".voice-period-selector button")].map((b) => b.textContent), ["전체", "일", "주", "월", "년"]);
    for (const [label, period] of [["전체", "ALL"], ["일", "DAY"], ["주", "WEEK"], ["월", "MONTH"], ["년", "YEAR"]]) {
      await clickPeriod(label);
      assert.equal(document.querySelector('.voice-period-selector [aria-pressed="true"]').textContent, label);
      await clickMember();
      const reference = { DAY: "2026-10-07", WEEK: "2026-10-05", MONTH: "2026-10-01", YEAR: "2026-01-01" }[period];
      assert.equal(page.requests.at(-1).url, `${endpoint}/members/1?period=${period}${reference ? `&referenceDate=${reference}` : ""}`);
      assert.equal(document.querySelector(".voice-detail-total strong").textContent,
        document.querySelector("tbody tr td:nth-child(2)").textContent);
      assert.match(document.querySelector(".voice-detail-period-note").textContent, /겹치는 시간만 합산/);
      assert.match(document.querySelector(".voice-session-list p").textContent, /10\/06 23:00 ~ 10\/07 02:00/);
    }
    await clickPeriod("전체");
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(page.requests.filter((r) => r.url === `${endpoint}?period=ALL`).length, 2);
    assert.equal(page.requests.every((r) => r.options.cache === "no-store"), true);
    assert.doesNotMatch(document.body.textContent, /최근 14일/);
  } finally { await page.close(); }
});

test("빠른 기간 전환 후 늦은 이전 목록 응답은 선택한 기간 결과를 덮어쓰지 않는다", async () => {
  let finishDay;
  const page = await mount((url) => {
    if (requestPeriod(url) === "DAY") return new Promise((done) => { finishDay = done; });
    return response([member(1, requestPeriod(url) === "WEEK" ? 3600 : 60)]);
  });
  try {
    await clickPeriod("일");
    assert.match(document.querySelector('[role="status"]').textContent, /불러오는 중/);
    await clickPeriod("주");
    await act(async () => finishDay(response([member(1, 7200)])));
    assert.equal(document.querySelector("tbody tr td:nth-child(2)").textContent, "1시간");
    assert.equal(document.querySelector('.voice-period-selector [aria-pressed="true"]').textContent, "주");
  } finally { await page.close(); }
});

test("닫은 상세의 늦은 응답은 새로 연 클랜원 상세를 덮어쓰지 않는다", async () => {
  let finishFirst;
  const page = await mount((url) => {
    if (url.includes("/members/1?")) return new Promise((done) => { finishFirst = done; });
    if (url.includes("/members/2?")) return response(detail(2, 120));
    return response([member(1), member(2, 120)]);
  });
  try {
    await clickMember();
    await act(async () => document.querySelector(".voice-modal-close").click());
    await clickMember(1);
    await act(async () => finishFirst(response(detail(1))));
    assert.equal(document.getElementById("voice-detail-title").textContent, "클랜원2");
    assert.match(document.querySelector(".voice-session-list").textContent, /채널2/);
  } finally { await page.close(); }
});

test("기간 변경은 이전 오류 상태를 초기화한다", async () => {
  const page = await mount((url) => requestPeriod(url) === "DAY"
    ? response({ message: "forbidden" }, 403) : response([member()]));
  try {
    await clickPeriod("일");
    assert.match(document.querySelector('[role="alert"]').textContent, /운영진과 관리자/);
    await clickPeriod("월");
    assert.equal(document.querySelector('[role="alert"]'), null);
    assert.ok(document.querySelector("tbody"));
  } finally { await page.close(); }
});

test("StrictMode 재마운트에서도 목록 요청은 한 번만 발생한다", async () => {
  const page = await mount(() => response([member()]), true);
  try {
    assert.equal(page.requests.length, 1);
    assert.ok(document.querySelector("tbody"));
  } finally { await page.close(); }
});

test("일·주·월·년 화살표는 실제 API 범위를 이동하고 현재 기간 복귀 버튼을 제공한다", async () => {
  const page = await mount(() => response([member()]));
  try {
    for (const [label, past, returnLabel] of [["일", "2026-10-06", "오늘"], ["주", "2026-09-28", "이번 주"],
      ["월", "2026-09-01", "이번 달"], ["년", "2025-01-01", "올해"]]) {
      await clickPeriod(label);
      assert.equal(document.querySelector('[aria-label="다음 기간"]').disabled, true);
      await clickLabel("이전 기간");
      assert.equal(new URL(page.requests.at(-1).url, "http://localhost").searchParams.get("referenceDate"), past);
      assert.equal(new URLSearchParams(window.location.search).get("referenceDate"), past);
      assert.match(document.querySelector(".voice-date-caption").textContent, /선택 기간 전체 집계/);
      assert.equal(document.querySelector(".voice-period-return").textContent.replace(" ↗", ""), returnLabel);
      await clickLabel("다음 기간");
      assert.equal(document.querySelector(".voice-period-return"), null);
      await clickLabel("이전 기간");
      await act(async () => document.querySelector(".voice-period-return").click());
      assert.equal(document.querySelector('[aria-label="다음 기간"]').disabled, true);
    }
    await clickPeriod("전체");
    assert.equal(document.querySelector(".voice-date-controls"), null);
    assert.equal(new URLSearchParams(window.location.search).has("referenceDate"), false);
  } finally { await page.close(); }
});

test("월 선택 팝업은 연도를 이동하고 과거 월을 선택하며 미래 월을 비활성화한다", async () => {
  const page = await mount(() => response([member()]));
  try {
    await clickPeriod("월");
    await clickLabel("월 선택");
    const buttons = [...document.querySelectorAll(".voice-period-grid button")];
    assert.equal(buttons.length, 12);
    assert.equal(buttons[9].getAttribute("aria-pressed"), "true");
    assert.equal(buttons[10].disabled, true);
    assert.equal(buttons[11].disabled, true);
    await clickLabel("선택 달력 이전");
    assert.match(document.querySelector(".voice-date-popover h3").textContent, /2025년/);
    await act(async () => [...document.querySelectorAll(".voice-period-grid button")].find((b) => b.textContent === "8월").click());
    assert.equal(document.querySelector(".voice-date-popover"), null);
    assert.match(document.querySelector(".voice-date-trigger").textContent, /2025년 8월/);
    assert.equal(page.requests.at(-1).url, `${endpoint}?period=MONTH&referenceDate=2025-08-01`);
  } finally { await page.close(); }
});

test("일 달력은 날짜를 직접 선택하고 주 달력은 월요일 기준으로 선택한다", async () => {
  const page = await mount((url) => response(url.includes("/members/") ? detail() : [member()]));
  try {
    await clickPeriod("일");
    await clickLabel("날짜 선택");
    assert.equal(document.querySelector('[aria-label="2026년 10월 8일"]').disabled, true);
    await clickLabel("2026년 10월 5일");
    assert.equal(page.requests.at(-1).url, `${endpoint}?period=DAY&referenceDate=2026-10-05`);
    await clickMember();
    assert.equal(page.requests.at(-1).url, `${endpoint}/members/1?period=DAY&referenceDate=2026-10-05`);
    await act(async () => document.querySelector(".voice-modal-close").click());
    await clickPeriod("주");
    await clickLabel("주 선택");
    await clickLabel("2026년 10월 1일");
    assert.equal(page.requests.at(-1).url, `${endpoint}?period=WEEK&referenceDate=2026-09-28`);
    assert.match(document.querySelector(".voice-date-trigger").textContent, /2026\.09\.28 ~ 2026\.10\.04/);
  } finally { await page.close(); }
});

test("연도 팝업은 선택 연도를 강조하고 미래 연도를 비활성화한다", async () => {
  const page = await mount(() => response([member()]));
  try {
    await clickPeriod("년");
    await clickLabel("연도 선택");
    const buttons = [...document.querySelectorAll(".voice-period-grid button")];
    assert.equal(buttons.find((b) => b.textContent === "2026년").getAttribute("aria-pressed"), "true");
    assert.equal(buttons.find((b) => b.textContent === "2027년").disabled, true);
    await act(async () => buttons.find((b) => b.textContent === "2025년").click());
    assert.equal(page.requests.at(-1).url, `${endpoint}?period=YEAR&referenceDate=2025-01-01`);
    assert.match(document.querySelector(".voice-period-return").textContent, /올해/);
  } finally { await page.close(); }
});

test("URL의 과거 월 선택은 같은 탭 재클릭과 새로 진입에서도 유지된다", async () => {
  const handler = () => response([member()]);
  let search;
  const page = await mount(handler, false, "communityId=9&period=MONTH&referenceDate=2026-08-01&keep=value");
  try {
    assert.equal(page.requests[0].url, `${endpoint}?period=MONTH&referenceDate=2026-08-01`);
    await clickPeriod("월");
    assert.match(document.querySelector(".voice-date-trigger").textContent, /2026년 8월/);
    await clickLabel("이전 기간");
    assert.equal(new URLSearchParams(window.location.search).get("keep"), "value");
    search = window.location.search.slice(1);
  } finally { await page.close(); }
  const reopened = await mount(handler, false, search);
  try {
    assert.equal(reopened.requests[0].url, `${endpoint}?period=MONTH&referenceDate=2026-07-01`);
    assert.match(document.querySelector(".voice-date-trigger").textContent, /2026년 7월/);
  } finally { await reopened.close(); }
});

test("과거 상세의 열린 세션은 현재 접속 표시 없이 퇴장 기록 없음을 안내한다", async () => {
  const page = await mount((url) => {
    const data = detail();
    data.sessions[0].leftAt = null;
    return response(url.includes("/members/") ? data : [member()]);
  }, false, "communityId=9&period=DAY&referenceDate=2026-10-05");
  try {
    assert.match(document.querySelector(".voice-panel-heading").textContent, /선택 기간 기준/);
    await clickMember();
    assert.match(document.querySelector(".voice-session-list").textContent, /퇴장 기록 없음/);
    assert.doesNotMatch(document.querySelector(".voice-session-list").textContent, /접속 중|활동 없음/);
  } finally { await page.close(); }
});

test("팝업은 Escape와 바깥 클릭으로 닫을 수 있다", async () => {
  const page = await mount(() => response([member()]));
  try {
    await clickPeriod("월");
    await clickLabel("월 선택");
    await act(async () => document.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape" })));
    assert.equal(document.querySelector(".voice-date-popover"), null);
    assert.equal(document.activeElement.getAttribute("aria-label"), "월 선택");
    await clickLabel("월 선택");
    await act(async () => document.body.dispatchEvent(new window.Event("pointerdown", { bubbles: true })));
    assert.equal(document.querySelector(".voice-date-popover"), null);
  } finally { await page.close(); }
});

test("같은 월 탭에서 빠르게 날짜를 이동해도 이전 날짜의 늦은 응답을 무시한다", async () => {
  let finishSeptember;
  const page = await mount((url) => {
    const date = new URL(url, "http://localhost").searchParams.get("referenceDate");
    if (date === "2026-09-01") return new Promise((done) => { finishSeptember = done; });
    return response([member(1, date === "2026-08-01" ? 7200 : 60)]);
  });
  try {
    await clickPeriod("월");
    await clickLabel("이전 기간");
    await clickLabel("이전 기간");
    await act(async () => finishSeptember(response([member(1, 3600)])));
    assert.match(document.querySelector(".voice-date-trigger").textContent, /2026년 8월/);
    assert.equal(document.querySelector("tbody tr td:nth-child(2)").textContent, "2시간");
  } finally { await page.close(); }
});

test("미래 기간이 담긴 URL도 미래 API 요청을 보내지 않는다", async () => {
  const page = await mount(() => response([member()]), false, "communityId=9&period=MONTH&referenceDate=2027-01-01");
  try {
    assert.equal(page.requests[0].url, `${endpoint}?period=MONTH&referenceDate=2026-10-01`);
    assert.equal(document.querySelector('[aria-label="다음 기간"]').disabled, true);
  } finally { await page.close(); }
});
