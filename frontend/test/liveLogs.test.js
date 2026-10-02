import test from "node:test";
import assert from "node:assert/strict";
import { connectLiveLogs, createLogBuffer, filterLogs, LOG_CAPACITY, LOG_STREAM_URL, MAX_LOG_MESSAGE, MAX_LOG_STACK, MAX_LOG_RECORD_CHARACTERS, parseLogEntry } from "../src/developer/liveLogs.js";

const record = (id, overrides = {}) => ({ id: `server:${id}`, timestamp: "2026-10-01T06:20:00Z", level: "INFO",
  category: "com.guildup.bingo", message: "aggregation started", stackTrace: "", thread: "bingo-worker", context: { bingoEventId: "7" }, ...overrides });

test("large batches retain only the newest 1000 log entries and discard replay duplicates", () => {
  const buffer = createLogBuffer();
  for (let index = 0; index < 10_000; index += 1) buffer.append(record(index));
  assert.equal(buffer.size, LOG_CAPACITY);
  assert.equal(buffer.snapshot()[0].id, "server:9000");
  assert.equal(buffer.snapshot().at(-1).id, "server:9999");
  assert.equal(buffer.append(record(9999)), false);
  assert.equal(buffer.size, LOG_CAPACITY);
});

test("a paused display remains unchanged while the ring continues with bounded storage", () => {
  const buffer = createLogBuffer(2);
  buffer.append(record(1));
  const pausedSnapshot = buffer.snapshot();
  buffer.append(record(2));
  buffer.append(record(3));
  assert.deepEqual(pausedSnapshot.map((row) => row.id), ["server:1"]);
  assert.deepEqual(buffer.snapshot().map((row) => row.id), ["server:2", "server:3"]);
  buffer.clear();
  assert.deepEqual(buffer.snapshot(), []);
  buffer.append(record(4));
  assert.equal(buffer.size, 1);
});

test("search finds categories, request IDs and stack traces and combines with the level filter", () => {
  const entries = [record(1), record(2, { level: "ERROR", stackTrace: "IllegalStateException",
    context: { requestId: "request-123" } })];
  assert.equal(filterLogs(entries, "ERROR", "request-123")[0].id, "server:2");
  assert.equal(filterLogs(entries, "ALL", "bingo").length, 2);
  assert.equal(filterLogs(entries, "WARN", "bingo").length, 0);
  assert.equal(filterLogs(entries, "ALL", "illegalstateexception").length, 1);
});

test("malformed stream records are rejected and large fields and credential context are bounded", () => {
  assert.throws(() => parseLogEntry("not-json"));
  assert.equal(parseLogEntry(JSON.stringify(record(1, { level: "DEBUG" }))), null);
  assert.equal(parseLogEntry(JSON.stringify(record(1, { timestamp: "invalid" }))), null);
  const parsed = parseLogEntry(JSON.stringify(record(1, {
    message: "x".repeat(MAX_LOG_MESSAGE + 100), stackTrace: "y".repeat(MAX_LOG_STACK + 100),
    context: { requestId: "req-1", authorization: "Bearer private", refreshToken: "private", smtpPassword: "private", sessionId: "private" },
  })));
  assert.equal(parsed.message.length, MAX_LOG_MESSAGE);
  assert.equal(parsed.stackTrace.length, MAX_LOG_STACK);
  assert.deepEqual(parsed.context, { requestId: "req-1" });
});

test("raw SSE records are bounded before JSON parsing", () => {
  const valid = JSON.stringify(record(1));
  const atLimit = valid + " ".repeat(MAX_LOG_RECORD_CHARACTERS - valid.length);
  assert.equal(parseLogEntry(atLimit).id, "server:1");
  assert.equal(parseLogEntry(atLimit + " "), null);
  assert.equal(parseLogEntry("invalid-json".repeat(MAX_LOG_RECORD_CHARACTERS)), null);
  assert.equal(parseLogEntry(record(1)), null);
  assert.equal(parseLogEntry(null), null);
});

test("native SSE reconnect retains one connection and cleanup closes it and suppresses late events", () => {
  const sources = [];
  class FakeEventSource {
    static CLOSED = 2;
    constructor(url, options) { this.url = url; this.options = options; this.listeners = new Map(); this.readyState = 0; sources.push(this); }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
    emit(name, data) { this.listeners.get(name)?.({ data }); }
    close() { this.closed = true; this.readyState = FakeEventSource.CLOSED; }
  }
  const entries = [], states = [], failures = [], gaps = [];
  const disconnect = connectLiveLogs({ EventSourceClass: FakeEventSource, onEntry: (row) => entries.push(row),
    onConnection: (state) => states.push(state), onInvalid: (error) => failures.push(error), onGap: () => gaps.push(true) });
  const source = sources[0];
  assert.equal(source.url, LOG_STREAM_URL);
  assert.deepEqual(source.options, { withCredentials: true });
  assert.ok(!source.url.includes("token"));
  source.onopen();
  source.emit("log", JSON.stringify(record(1)));
  source.emit("log", "bad-json");
  source.onerror();
  source.onopen();
  source.emit("gap");
  assert.deepEqual(states, ["connected", "reconnecting", "connected"]);
  assert.equal(sources.length, 1);
  assert.equal(entries.length, 1);
  assert.equal(failures.length, 1);
  assert.equal(gaps.length, 1);
  disconnect();
  source.emit("log", JSON.stringify(record(2)));
  source.onerror();
  assert.equal(source.closed, true);
  assert.equal(entries.length, 1);
  assert.equal(states.length, 3);
});
