export const LOG_CAPACITY = 1000;
export const LOG_STREAM_URL = "/api/developer/monitoring/logs/stream";
export const MAX_LOG_MESSAGE = 4096;
export const MAX_LOG_STACK = 8192;
export const MAX_LOG_RECORD_CHARACTERS = 32768;

function boundedText(value, limit) {
  return typeof value === "string" ? value.slice(0, limit) : "";
}

// Defend the browser too: a corrupt SSE record must not crash the page or
// introduce unbounded strings/objects into a long-lived monitoring tab.
export function parseLogEntry(data) {
  if (typeof data !== "string" || data.length > MAX_LOG_RECORD_CHARACTERS) return null;
  const value = JSON.parse(data);
  if (!value || !["INFO", "WARN", "ERROR"].includes(value.level)
    || typeof value.id !== "string" || !value.id || value.id.length > 100
    || typeof value.timestamp !== "string" || value.timestamp.length > 80
    || !Number.isFinite(Date.parse(value.timestamp))) return null;
  const context = {};
  for (const [key, item] of Object.entries(value.context || {}).slice(0, 30)) {
    if (!/^[A-Za-z][A-Za-z0-9]{0,40}$/.test(key)) continue;
    if (/token|secret|password|authorization|cookie|session/i.test(key)) continue;
    if (typeof item === "string" || typeof item === "number" || typeof item === "boolean") {
      context[key] = String(item).slice(0, 200);
    }
  }
  return {
    id: value.id,
    timestamp: value.timestamp,
    level: value.level,
    category: boundedText(value.category, 160),
    thread: boundedText(value.thread, 100),
    message: boundedText(value.message, MAX_LOG_MESSAGE),
    stackTrace: boundedText(value.stackTrace, MAX_LOG_STACK),
    context,
  };
}

export function createLogBuffer(capacity = LOG_CAPACITY) {
  const limit = Math.max(1, Math.min(LOG_CAPACITY, Math.floor(Number(capacity) || LOG_CAPACITY)));
  const records = new Array(limit);
  const ids = new Set();
  let start = 0;
  let count = 0;
  let version = 0;
  return {
    append(entry) {
      if (!entry || ids.has(entry.id)) return false;
      if (count === limit) {
        ids.delete(records[start].id);
        records[start] = entry;
        start = (start + 1) % limit;
      } else {
        records[(start + count) % limit] = entry;
        count += 1;
      }
      ids.add(entry.id);
      version += 1;
      return true;
    },
    snapshot() {
      return Array.from({ length: count }, (_, index) => records[(start + index) % limit]);
    },
    clear() {
      records.fill(undefined);
      ids.clear();
      start = 0;
      count = 0;
      version += 1;
    },
    get version() { return version; },
    get size() { return count; },
  };
}

export function filterLogs(entries, level = "ALL", search = "") {
  const keyword = search.trim().toLocaleLowerCase();
  return entries.filter((entry) => (level === "ALL" || entry.level === level)
    && (!keyword || [entry.category, entry.message, entry.stackTrace, entry.thread,
      ...Object.entries(entry.context).flat()].join(" ").toLocaleLowerCase().includes(keyword)));
}

// Keep the same EventSource alive across transient failures, so its native
// Last-Event-ID/reconnect behavior can resume the server's bounded replay.
export function connectLiveLogs({ onEntry, onConnection, onGap, onInvalid, EventSourceClass = globalThis.EventSource }) {
  let active = true;
  const source = new EventSourceClass(LOG_STREAM_URL, { withCredentials: true });
  source.onopen = () => { if (active) onConnection("connected"); };
  source.addEventListener("ready", () => { if (active) onConnection("connected"); });
  source.addEventListener("log", (event) => {
    if (!active) return;
    try {
      const entry = parseLogEntry(event.data);
      if (!entry) throw new Error("Invalid log record");
      onEntry(entry);
    } catch (failure) { onInvalid(failure); }
  });
  source.addEventListener("gap", () => { if (active) onGap(); });
  source.onerror = () => {
    if (active) onConnection(source.readyState === EventSourceClass.CLOSED ? "closed" : "reconnecting");
  };
  return () => { active = false; source.close(); };
}
