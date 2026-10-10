import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { api } from "../src/api/http.js";

const tokenResponse = (token = "session-token") => new Response(JSON.stringify({ token }), { status: 200 });

for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
  test(`${method} obtains a session token and preserves caller headers, body and signal`, async (t) => {
    const calls = [];
    const signal = new AbortController().signal;
    t.mock.method(globalThis, "fetch", async (url, options) => {
      calls.push({ url, options });
      return url === "/api/auth/csrf" ? tokenResponse() : new Response(null, { status: 204 });
    });
    await api("/api/communities/1/test", { method, headers: { "Content-Type": "application/json" }, body: "{}", signal });
    assert.equal(calls.length, 2);
    assert.equal(calls[0].url, "/api/auth/csrf");
    assert.equal(calls[0].options.cache, "no-store");
    assert.equal(new Headers(calls[1].options.headers).get("X-CSRF-Token"), "session-token");
    assert.equal(new Headers(calls[1].options.headers).get("Content-Type"), "application/json");
    assert.equal(calls[1].options.body, "{}");
    assert.equal(calls[1].options.signal, signal);
    assert.equal(calls[1].options.credentials, "same-origin");
  });
}

test("read requests do not fetch or transmit CSRF tokens", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, options }); return new Response("{}", { status: 200 });
  });
  await api("/api/auth/me");
  assert.equal(calls.length, 1);
  assert.equal(new Headers(calls[0].options.headers).has("X-CSRF-Token"), false);
});

test("anonymous token issuance never grants access to a protected mutation after logout", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, options });
    return url === "/api/auth/csrf" ? tokenResponse("anonymous-token")
      : new Response('{"message":"로그인이 필요합니다."}', { status: 401 });
  });
  await assert.rejects(api("/api/communities", { method: "POST", body: "{}" }), (error) => error.status === 401);
  assert.deepEqual(calls.map((item) => item.url), ["/api/auth/csrf", "/api/communities"]);
  assert.equal(new Headers(calls[1].options.headers).get("X-CSRF-Token"), "anonymous-token");
});

test("expired authentication stops mutation and does not retry a rejected mutation", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    calls.push(url); return new Response('{"message":"Login required"}', { status: 401 });
  });
  await assert.rejects(api("/api/auth/logout", { method: "POST" }), (error) => error.status === 401);
  assert.deepEqual(calls, ["/api/auth/csrf"]);
  calls.length = 0;
  globalThis.fetch = async (url) => {
    calls.push(url); return url === "/api/auth/csrf" ? tokenResponse()
      : new Response('{"code":"CSRF_TOKEN_INVALID","message":"Refresh login"}', { status: 403 });
  };
  await assert.rejects(api("/api/auth/logout", { method: "POST" }), (error) => error.code === "CSRF_TOKEN_INVALID");
  assert.deepEqual(calls, ["/api/auth/csrf", "/api/auth/logout"]);
});

test("each mutation obtains the current session token after login or logout", async (t) => {
  let token = "first-session";
  const tokens = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    if (url === "/api/auth/csrf") return tokenResponse(token);
    tokens.push(new Headers(options.headers).get("X-CSRF-Token"));
    return new Response(null, { status: 204 });
  });
  await api("/api/auth/logout", { method: "post" });
  token = "new-session";
  await api("/api/communities", { method: "POST", headers: new Headers({ "Content-Type": "application/json" }) });
  assert.deepEqual(tokens, ["first-session", "new-session"]);
});

test("legacy common API also supplies a CSRF header for all mutation methods", async () => {
  const helper = readFileSync(new URL("../../src/main/resources/static/community-ui.js", import.meta.url), "utf8");
  const calls = [];
  const browser = { performance, URL, Headers, console, location: { origin: "https://guildup.test", pathname: "/members.html" },
    fetch: async (url, options) => {
      calls.push({ url, options }); return url === "/api/auth/csrf" ? tokenResponse() : new Response(null, { status: 204 });
    } };
  runInNewContext(helper, browser);
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    await browser.api("/api/communities/1/test", { method, headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(new Headers(calls.at(-1).options.headers).get("X-CSRF-Token"), "session-token");
    assert.equal(new Headers(calls.at(-1).options.headers).get("Content-Type"), "application/json");
  }
  assert.equal(calls.length, 8);
});

test("tokens are not automatically sent to a foreign origin", async (t) => {
  const previous = globalThis.location;
  globalThis.location = { origin: "https://guildup.test" };
  t.after(() => { if (previous) globalThis.location = previous; else delete globalThis.location; });
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, options }); return new Response(null, { status: 204 });
  });
  await api("https://foreign.test/api/test", { method: "POST" });
  assert.equal(calls.length, 1);
  assert.equal(new Headers(calls[0].options.headers).has("X-CSRF-Token"), false);
});

test("token lookup cancellation prevents the mutation", async (t) => {
  const cancellation = new DOMException("cancelled", "AbortError");
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url) => { calls.push(url); throw cancellation; });
  await assert.rejects(api("/api/communities/1/attendance", { method: "POST" }), (error) => error === cancellation);
  assert.deepEqual(calls, ["/api/auth/csrf"]);
});

test("403 exposes a correlation ID without retrying or logging credentials", async (t) => {
  const calls = [];
  const diagnostics = [];
  t.mock.method(console, "warn", (...args) => diagnostics.push(args));
  t.mock.method(globalThis, "fetch", async (url) => {
    calls.push(url);
    return url === "/api/auth/csrf" ? tokenResponse("private-security-value")
      : new Response('{"code":"CSRF_TOKEN_INVALID","message":"보안 정보를 다시 확인해 주세요."}',
        { status: 403, headers: { "X-Request-ID": "server-request-1234" } });
  });
  await assert.rejects(api("/api/auth/login", { method: "POST", body: '{"password":"private-password"}' }),
    (error) => error.status === 403 && error.code === "CSRF_TOKEN_INVALID"
      && error.requestId === "server-request-1234" && error.message.includes("요청 ID: server-request-1234"));
  assert.deepEqual(calls, ["/api/auth/csrf", "/api/auth/login"]);
  assert.match(JSON.stringify(diagnostics), /HTTP_ACCESS_DENIED/);
  assert.doesNotMatch(JSON.stringify(diagnostics), /private-password|private-security-value/);
});
