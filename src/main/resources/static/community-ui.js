async function api(url, options = {}) {
    const startedAt = performance.now();
    const method = String(options.method || "GET").toUpperCase();
    const target = new URL(url, location.origin);
    if (!["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)
            && target.origin === location.origin && /^\/api(?:\/|$)/.test(target.pathname)) {
        const csrf = await api("/api/auth/csrf", {cache: "no-store", signal: options.signal});
        if (typeof csrf?.token !== "string" || !csrf.token) {
            throw new Error("요청 보안 정보를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.");
        }
        const headers = new Headers(options.headers);
        headers.set("X-CSRF-Token", csrf.token);
        options = {...options, headers};
    }
    let response;
    try { response = await fetch(url, {credentials: "same-origin", ...options}); }
    catch (failure) {
        if (failure.name === "AbortError") throw failure;
        const error = new Error("서버에 연결하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.");
        error.status = 0;
        reportLegacyFailure(url, options.method, 0, null, performance.now() - startedAt);
        throw error;
    }
    const candidateId = response.headers.get("X-Request-ID");
    const requestId = /^[A-Za-z0-9._:-]{1,80}$/.test(candidateId || "") ? candidateId : null;
    if (response.status === 401) {
        if (location.pathname !== "/login.html") location.replace("/login.html");
    }
    if (!response.ok) {
        let message = response.status === 401 ? "로그인이 필요합니다."
            : response.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다."
            : "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        if (response.status >= 500) {
            if (requestId) message += ` (요청 ID: ${requestId})`;
            reportLegacyFailure(url, options.method, response.status, requestId, performance.now() - startedAt);
        } else {
            const body = await response.json().catch((failure) => {
                if (failure?.name === "AbortError") throw failure;
                return null;
            });
            if (typeof body?.message === "string") message = body.message;
        }
        const error = new Error(message);
        error.status = response.status;
        error.requestId = requestId;
        throw error;
    }
    if (response.status === 204) return null;
    try { return await response.json(); }
    catch (failure) {
        if (failure?.name === "AbortError") throw failure;
        reportLegacyFailure(url, options.method, response.status, requestId, performance.now() - startedAt);
        throw new Error("서버 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
const legacyDiagnostics = new Map();
function reportLegacyFailure(url, method, status, requestId, elapsedMs) {
    let endpoint;
    try { endpoint = new URL(url, location.origin).pathname.replace(/(\/oauth\/results\/)[^/]+/gi, "$1[redacted]").slice(0, 240); }
    catch { endpoint = "[invalid endpoint]"; }
    const now = Date.now();
    for (const [key, time] of legacyDiagnostics) if (now - time >= 30000) legacyDiagnostics.delete(key);
    const key = `${endpoint}:${status}`;
    if (legacyDiagnostics.has(key) || legacyDiagnostics.size >= 30) return;
    legacyDiagnostics.set(key, now);
    try { console.warn("[GuildUp] Client operation failed", {endpoint, method: /^[A-Z]{1,12}$/.test(method || "GET") ? method || "GET" : undefined, status, requestId, elapsedMs: Math.round(elapsedMs)}); }
    catch { /* Browser diagnostics must not replace the original error. */ }
}
function showError(error) {
    document.getElementById("message").textContent = error.message;
}
async function logout() {
    try { await api("/api/auth/logout", {method:"POST"}); location.replace("/login.html"); }
    catch (error) { showError(error); }
}
