import { reportClientFailure, safeEndpoint, safeRequestId } from "./diagnostics.js";

export class ApiError extends Error {
  constructor(status, message, details = {}) {
    super(message);
    this.status = status;
    this.code = details.code ?? null;
    this.communityId = details.communityId ?? null;
    this.communityName = details.communityName ?? null;
    this.requestId = safeRequestId(details.requestId);
    this.endpoint = details.endpoint ?? null;
    this.method = details.method ?? null;
    this.elapsedMs = details.elapsedMs ?? null;
    this.name = "ApiError";
  }
}

export async function api(url, options = {}) {
  const startedAt = performance.now();
  const context = { endpoint: safeEndpoint(url), method: String(options.method || "GET").toUpperCase() };
  let response;
  try {
    response = await fetch(url, { credentials: "same-origin", ...options });
  } catch (failure) {
    if (failure?.name === "AbortError") throw failure;
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
      if (failure?.name === "AbortError") throw failure;
      return null;
    });
    if (response.status >= 500) {
      message = "서버 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
      if (context.requestId) message += ` (요청 ID: ${context.requestId})`;
    } else if (body && typeof body.message === "string") message = body.message;
    else if (body && typeof body.detail === "string") message = body.detail;
    context.elapsedMs = performance.now() - startedAt;
    const error = new ApiError(response.status, message, { ...(body || {}), ...context });
    if (response.status >= 500) reportClientFailure("HTTP_SERVER_ERROR", error, context);
    throw error;
  }

  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch (failure) {
    if (failure?.name === "AbortError") throw failure;
    context.elapsedMs = performance.now() - startedAt;
    const message = "서버 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
      + (context.requestId ? ` (요청 ID: ${context.requestId})` : "");
    const error = new ApiError(response.status, message,
      { ...context, code: "INVALID_RESPONSE" });
    reportClientFailure("INVALID_RESPONSE", error, context);
    throw error;
  }
}

export function redirectToLogin(error) {
  if (error instanceof ApiError && error.status === 401) {
    window.location.replace("/login.html");
    return true;
  }
  return false;
}
