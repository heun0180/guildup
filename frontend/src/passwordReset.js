// Keep link secrets only in memory; never in router state, storage, diagnostics or request URLs.
let linkToken = "";
export function resetLinkToken() {
  if (typeof window !== "undefined" && window.location.pathname === "/password-reset.html") {
    if (window.__guildUpResetToken !== undefined || window.location.hash) {
      linkToken = window.__guildUpResetToken ?? new URLSearchParams(window.location.hash.slice(1)).get("token") ?? "";
      delete window.__guildUpResetToken;
      window.history.replaceState(null, "", window.location.pathname);
    }
  }
  return linkToken;
}
resetLinkToken();
export function clearResetLinkToken() { linkToken = ""; }
export const RESET_ACCEPTED_MESSAGE = "입력하신 이메일로 재설정 안내를 보낼 수 있는 경우 잠시 후 이메일이 발송됩니다.";
