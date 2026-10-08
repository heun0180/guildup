import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { api, ApiError, parseRetryAfter } from "../src/api/http.js";

test("Retry-After accepts delay seconds and HTTP dates and rejects invalid or excessive values", () => {
  const now = Date.parse("2026-10-08T00:00:00Z");
  assert.equal(parseRetryAfter("30", now), 30);
  assert.equal(parseRetryAfter("Thu, 08 Oct 2026 00:00:10 GMT", now), 10);
  assert.equal(parseRetryAfter("", now), null);
  assert.equal(parseRetryAfter("invalid", now), null);
  assert.equal(parseRetryAfter("-1", now), null);
  assert.equal(parseRetryAfter("999999999999999", now), 3600);
});

test("HTTP 429 exposes Retry-After without retrying requests or logging credentials", async (t) => {
  const logs = [], requests = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  t.mock.method(globalThis, "fetch", async (url) => {
    requests.push(url);
    if (url === "/api/auth/csrf") return new Response('{"token":"test-csrf"}');
    return new Response('{"code":"LOGIN_RATE_LIMITED","message":"잠시 후 다시 시도해 주세요."}',
      { status: 429, headers: { "Retry-After": "12" } });
  });
  await assert.rejects(api("/api/auth/login", { method: "POST", body: "test-private-body" }),
    (error) => error.status === 429 && error.retryAfterSeconds === 12 && error.code === "LOGIN_RATE_LIMITED");
  assert.deepEqual(requests, ["/api/auth/csrf", "/api/auth/login"]);
  assert.equal(logs.length, 0);
});
import { reportClientFailure, safeEndpoint, safeRequestId } from "../src/api/diagnostics.js";

test("server failures keep the request ID while hiding internal messages and credentials", async (t) => {
  const logs = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  t.mock.method(globalThis, "fetch", async () => new Response(JSON.stringify({
    message: "SQL rollback: password=private-secret Authorization: Bearer secret", requestId: "req-500",
  }), { status: 500, headers: { "Content-Type": "application/json", "X-Request-ID": "req-500" } }));
  await assert.rejects(api("/api/diagnostic-http-test?access_token=query-secret"), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 500);
    assert.equal(error.requestId, "req-500");
    assert.match(error.message, /req-500/);
    assert.ok(!error.message.includes("private-secret"));
    return true;
  });
  const output = JSON.stringify(logs);
  assert.ok(output.includes("req-500"));
  assert.ok(!output.includes("private-secret"));
  assert.ok(!output.includes("query-secret"));
  assert.ok(!output.includes("Authorization"));
});

test("network and invalid JSON failures have different context without logging arbitrary error text", async (t) => {
  const logs = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  t.mock.method(globalThis, "fetch", async (url) => {
    if (url === "/api/auth/csrf") return new Response('{"token":"session-token"}', { status: 200 });
    throw new TypeError("private-network-secret");
  });
  await assert.rejects(api("/api/network-test", { method: "POST", body: "private-body-secret" }),
    (error) => error.code === "NETWORK_ERROR" && error.method === "POST" && error.status === 0);
  globalThis.fetch = async () => new Response("invalid-json-secret", { status: 200 });
  await assert.rejects(api("/api/parse-test"), (error) => error.code === "INVALID_RESPONSE" && error.status === 200);
  assert.deepEqual(logs.map((entry) => entry[1].kind), ["NETWORK_ERROR", "INVALID_RESPONSE"]);
  assert.ok(!JSON.stringify(logs).includes("secret"));
});

test("expected HTTP client errors and cancelled requests do not flood diagnostics", async (t) => {
  const logs = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  t.mock.method(globalThis, "fetch", async () => new Response(JSON.stringify({ message: "입력 내용을 확인해 주세요." }), { status: 400 }));
  await assert.rejects(api("/api/validation-test"), (error) => error.status === 400 && error.message.includes("입력"));
  globalThis.fetch = async () => { throw new DOMException("cancelled", "AbortError"); };
  await assert.rejects(api("/api/cancel-test"), (error) => error.name === "AbortError");
  assert.equal(logs.length, 0);
});

test("cancellation while reading a response body stays an AbortError without parsing diagnostics", async (t) => {
  const logs = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  for (const status of [200, 400, 500]) {
    const cancellation = new DOMException("request cancelled", "AbortError");
    t.mock.method(globalThis, "fetch", async () => ({
      status, ok: status < 400, headers: new Headers(), json: async () => { throw cancellation; },
    }));
    await assert.rejects(api(`/api/body-cancel-test/${status}`), (error) => error === cancellation);
  }
  assert.equal(logs.length, 0);
});

test("legacy pages also preserve body-read cancellation without reporting a failure", async () => {
  const logs = [];
  const helper = readFileSync(new URL("../../src/main/resources/static/community-ui.js", import.meta.url), "utf8");
  const browser = {
    console: { warn: (...args) => logs.push(args) }, performance,
    location: { pathname: "/members.html", origin: "https://guildup.test" }, URL,
  };
  runInNewContext(helper, browser);
  for (const status of [200, 400]) {
    const cancellation = new DOMException("request cancelled", "AbortError");
    browser.fetch = async () => ({
      status, ok: status < 400, headers: new Headers(), json: async () => { throw cancellation; },
    });
    await assert.rejects(browser.api(`/api/legacy-body-cancel-test/${status}`), (error) => error === cancellation);
  }
  assert.equal(logs.length, 0);
});

test("successful and empty responses preserve behavior and same-origin authentication", async (t) => {
  const requests = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    requests.push({ url, options });
    return url.endsWith("empty") ? new Response(null, { status: 204 }) : new Response('{"ok":true}', { status: 200 });
  });
  assert.deepEqual(await api("/api/success"), { ok: true });
  assert.equal(await api("/api/empty"), null);
  assert.equal(requests[0].options.credentials, "same-origin");
  assert.equal(requests.length, 2);
});

test("a failed diagnostic sink cannot replace the original API exception", async (t) => {
  t.mock.method(console, "warn", () => { throw new Error("diagnostic sink failed"); });
  t.mock.method(globalThis, "fetch", async () => new Response("{}", { status: 500 }));
  await assert.rejects(api("/api/diagnostic-sink-test"), (error) => error instanceof ApiError && error.status === 500);
});

test("diagnostics omit OAuth result tokens and query/header credentials and throttle repeated failures", (t) => {
  const logs = [];
  t.mock.method(console, "warn", (...args) => logs.push(args));
  assert.equal(safeEndpoint("https://user:password@example.test/api/discord/oauth/results/private-token?code=secret"),
    "/api/discord/oauth/results/[redacted]");
  assert.equal(safeRequestId("\nsecret"), null);
  for (let index = 0; index < 100; index += 1) reportClientFailure("POLLING_TEST", new Error("private"), {
    endpoint: "/api/repeated-test?token=private", status: 500, authorization: "Bearer private", body: "private",
  });
  assert.equal(logs.length, 1);
  assert.ok(!JSON.stringify(logs).includes("private"));
});
