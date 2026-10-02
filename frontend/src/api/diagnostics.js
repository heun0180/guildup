const reportedErrors = new WeakSet();
const recentDiagnostics = new Map();
const MAX_DIAGNOSTICS = 30;
const REPEAT_WINDOW_MS = 30_000;
const ERROR_TYPES = new Set(["Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError", "URIError", "EvalError", "ApiError", "DOMException"]);

// Only identifiers are recorded. Messages, stacks, payloads, cookies and headers
// can contain user input or credentials and must never reach the console.
export function safeEndpoint(value) {
  let path;
  try { path = new URL(String(value || ""), "https://guildup.invalid").pathname; }
  catch { return "[invalid endpoint]"; }
  return path.replace(/(\/oauth\/results\/)[^/]+/gi, "$1[redacted]")
    .replace(/(\/(?:tokens?|secrets?|sessions?)\/)[^/]+/gi, "$1[redacted]")
    .replace(/[\r\n\t]/g, "").slice(0, 240);
}

export function safeRequestId(value) {
  return typeof value === "string" && /^[A-Za-z0-9._:-]{1,80}$/.test(value) ? value : null;
}

export function reportClientFailure(kind, error, context = {}) {
  if (error?.name === "AbortError") return;
  if (error && typeof error === "object") {
    if (reportedErrors.has(error)) return;
    reportedErrors.add(error);
  }
  const metadata = {
    kind,
    endpoint: safeEndpoint(context.endpoint ?? globalThis.location?.pathname),
    method: /^[A-Z]{1,12}$/.test(context.method || "") ? context.method : undefined,
    status: Number.isInteger(context.status) ? context.status : undefined,
    requestId: safeRequestId(context.requestId),
    elapsedMs: Number.isFinite(context.elapsedMs) ? Math.round(context.elapsedMs) : undefined,
    errorType: ERROR_TYPES.has(error?.name) ? error.name : "Error",
  };
  const now = Date.now();
  for (const [key, time] of recentDiagnostics) {
    if (now - time >= REPEAT_WINDOW_MS) recentDiagnostics.delete(key);
  }
  const key = `${kind}:${metadata.endpoint}:${metadata.method}:${metadata.status}`;
  if (recentDiagnostics.has(key) || recentDiagnostics.size >= MAX_DIAGNOSTICS) return;
  recentDiagnostics.set(key, now);
  try { console.warn("[GuildUp] Client operation failed", metadata); }
  catch { /* Diagnostics must not replace the original request failure. */ }
}
