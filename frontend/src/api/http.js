import { reportClientFailure, safeEndpoint, safeRequestId } from "./diagnostics.js";
import { isRequestCancelled } from "./requestScope.js";

export { isRequestCancelled };

export class ApiError extends Error {
  constructor(status, message, details = {}) {
    super(message);
    this.status = status;
    this.code = details.code ?? null;
    this.communityId = details.communityId ?? null;
    this.communityName = details.communityName ?? null;
    this.ownedCommunities = details.ownedCommunities ?? [];
    this.requestId = safeRequestId(details.requestId);
    this.endpoint = details.endpoint ?? null;
    this.method = details.method ?? null;
    this.elapsedMs = details.elapsedMs ?? null;
    this.retryAfterSeconds = details.retryAfterSeconds ?? null;
    this.name = "ApiError";
  }
}

export async function api(url, options = {}) {
  const startedAt = performance.now();
  const context = { endpoint: safeEndpoint(url), method: String(options.method || "GET").toUpperCase() };
  if (!["GET", "HEAD", "OPTIONS", "TRACE"].includes(context.method) && isSameOriginApi(url)) {
    // 익명 회원가입/로그인도 같은 세션 토큰을 사용한다. 다른 탭의 로그인 변경에 맞춰 매번 읽는다.
    const csrf = await api("/api/auth/csrf", { cache: "no-store", signal: options.signal });
    if (typeof csrf?.token !== "string" || !csrf.token) {
      throw new ApiError(200, "요청 보안 정보를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.",
        { ...context, code: "INVALID_RESPONSE" });
    }
    const headers = new Headers(options.headers);
    headers.set("X-CSRF-Token", csrf.token);
    options = { ...options, headers };
  }
  let response;
  try {
    response = await fetch(url, { credentials: "same-origin", ...options });
  } catch (failure) {
    if (isRequestCancelled(failure)) throw failure;
    context.elapsedMs = performance.now() - startedAt;
    const error = new ApiError(0, "서버에 연결하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.",
      { ...context, code: "NETWORK_ERROR" });
    reportClientFailure("NETWORK_ERROR", error, context);
    throw error;
  }
  context.status = response.status;
  context.requestId = safeRequestId(response.headers.get("X-Request-ID"));
  context.elapsedMs = performance.now() - startedAt;

  if (!response.ok) {
    let message = `요청을 처리하지 못했습니다. (HTTP ${response.status})`;
    const body = await response.json().catch((failure) => {
      if (isRequestCancelled(failure)) throw failure;
      return null;
    });
    if (response.status >= 500) {
      message = "서버 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
      if (context.requestId) message += ` (요청 ID: ${context.requestId})`;
    } else if (body && typeof body.message === "string") message = body.message;
    else if (body && typeof body.detail === "string") message = body.detail;
    context.elapsedMs = performance.now() - startedAt;
    const error = new ApiError(response.status, message, { ...(body || {}), ...context,
      retryAfterSeconds: parseRetryAfter(response.headers.get("Retry-After")) });
    if (response.status >= 500) reportClientFailure("HTTP_SERVER_ERROR", error, context);
    throw error;
  }

  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch (failure) {
    if (isRequestCancelled(failure)) throw failure;
    context.elapsedMs = performance.now() - startedAt;
    const message = "서버 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
      + (context.requestId ? ` (요청 ID: ${context.requestId})` : "");
    const error = new ApiError(response.status, message,
      { ...context, code: "INVALID_RESPONSE" });
    reportClientFailure("INVALID_RESPONSE", error, context);
    throw error;
  }
}

function isSameOriginApi(url) {
  const origin = globalThis.location?.origin;
  if (!origin) return typeof url === "string" && /^\/api(?:\/|$)/.test(url);
  const target = new URL(url, origin);
  return target.origin === origin && /^\/api(?:\/|$)/.test(target.pathname);
}

export function parseRetryAfter(value, now = Date.now()) {
  if (typeof value !== "string" || !value.trim()) return null;
  const seconds = /^\d+$/.test(value) ? Number(value) : Math.ceil((Date.parse(value) - now) / 1000);
  return Number.isFinite(seconds) && seconds >= 0 ? Math.min(3600, Math.max(1, seconds)) : null;
}

export function redirectToLogin(error) {
  if (error instanceof ApiError && error.code === "EMAIL_VERIFICATION_REQUIRED") {
    window.location.replace("/email-verification.html");
    return true;
  }
  if (error instanceof ApiError && error.status === 401) {
    window.location.replace("/login.html");
    return true;
  }
  return false;
}
