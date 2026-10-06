import test from "node:test";
import assert from "node:assert/strict";
import { startActivitySyncPolling, ACTIVITY_SYNC_POLL_INTERVAL_MS } from "../src/activitySyncPolling.js";

function harness(loadActivities, onError = () => {}) {
  const timers = new Map();
  let next = 0;
  const updates = [], completed = [];
  const stop = startActivitySyncPolling({ loadActivities,
    onUpdate: (value) => updates.push(value), onComplete: (value) => completed.push(value), onError,
    schedule: (callback, delay) => { assert.equal(delay, 3000); timers.set(++next, callback); return next; },
    clearSchedule: (id) => timers.delete(id),
  });
  return { updates, completed, timers, stop, async tick() {
    const [id, callback] = timers.entries().next().value;
    timers.delete(id);
    await callback();
  } };
}

test("polls every three seconds and replaces stored activities before announcing SUCCESS", async () => {
  const results = [ { sync: { status: "SYNCING" }, members: ["old"] },
    { sync: { status: "SUCCESS" }, members: ["new"] } ];
  const polling = harness(async (options) => {
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal.aborted, false);
    return results.shift();
  });
  assert.equal(ACTIVITY_SYNC_POLL_INTERVAL_MS, 3000);
  assert.equal(polling.updates.length, 0);
  await polling.tick();
  assert.equal(polling.completed.length, 0);
  assert.equal(polling.timers.size, 1);
  await polling.tick();
  assert.deepEqual(polling.updates[1].members, ["new"]);
  assert.equal(polling.completed[0].status, "SUCCESS");
  assert.equal(polling.timers.size, 0);
  polling.stop();
});

test("FAILED keeps the server snapshot and stops polling", async () => {
  const polling = harness(async () => ({ sync: { status: "FAILED" }, members: ["previous"] }));
  await polling.tick();
  assert.deepEqual(polling.updates[0].members, ["previous"]);
  assert.equal(polling.completed[0].status, "FAILED");
  assert.equal(polling.timers.size, 0);
  polling.stop();
});

test("transient GET failure does not mark the server job failed and polling resumes", async () => {
  let attempts = 0;
  const errors = [];
  const polling = harness(async () => {
    if (++attempts === 1) throw new Error("connection lost");
    return { sync: { status: "SUCCESS" }, members: ["new"] };
  }, (error) => errors.push(error));
  await polling.tick();
  assert.equal(errors.length, 1);
  assert.equal(polling.completed.length, 0);
  await polling.tick();
  assert.equal(polling.completed[0].status, "SUCCESS");
  polling.stop();
});

test("only one GET is in flight and leaving aborts it and ignores a late response", async () => {
  let resolve, signal;
  const polling = harness((options) => {
    signal = options.signal;
    return new Promise((done) => { resolve = done; });
  });
  const pending = polling.tick();
  assert.equal(polling.timers.size, 0);
  polling.stop();
  assert.equal(signal.aborted, true);
  resolve({ sync: { status: "SUCCESS" } });
  await pending;
  assert.equal(polling.updates.length, 0);
  assert.equal(polling.completed.length, 0);
  assert.equal(polling.timers.size, 0);
});

test("authorization failures can stop polling without changing server state", async () => {
  const polling = harness(async () => { throw Object.assign(new Error("forbidden"), { status: 403 }); }, () => false);
  await polling.tick();
  assert.equal(polling.timers.size, 0);
  assert.equal(polling.completed.length, 0);
  polling.stop();
});
