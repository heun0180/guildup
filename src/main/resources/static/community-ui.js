async function api(url, options = {}) {
    const response = await fetch(url, {credentials: "same-origin", ...options});
    if (response.status === 401) {
        location.replace("/login.html");
        throw new Error("로그인이 필요합니다.");
    }
    if (response.status === 403) throw new Error("이 커뮤니티에 접근할 권한이 없습니다.");
    if (!response.ok) throw new Error("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    return response.status === 204 ? null : response.json();
}
function showError(error) {
    document.getElementById("message").textContent = error.message;
}
async function logout() {
    try { await api("/api/auth/logout", {method:"POST"}); location.replace("/login.html"); }
    catch (error) { showError(error); }
}
