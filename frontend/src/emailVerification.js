// Read the fragment once, remove it before any API/third party request, and keep it only in memory.
let linkToken = "";
if (typeof window !== "undefined" && window.location.pathname === "/email-verification.html") {
  linkToken = window.__guildUpVerificationToken ?? new URLSearchParams(window.location.hash.slice(1)).get("token") ?? "";
  delete window.__guildUpVerificationToken;
  if (window.location.hash) window.history.replaceState(null, "", window.location.pathname);
}
export function verificationLinkToken() {
  // Also handles client navigation and tests mounting the route after the bundle loaded.
  if (typeof window !== "undefined" && window.location.pathname === "/email-verification.html"
      && (window.__guildUpVerificationToken !== undefined || window.location.hash)) {
    linkToken = window.__guildUpVerificationToken ?? new URLSearchParams(window.location.hash.slice(1)).get("token") ?? "";
    delete window.__guildUpVerificationToken;
    window.history.replaceState(null, "", window.location.pathname);
  }
  return linkToken;
}
export function clearVerificationLinkToken() { linkToken = ""; }
export function verificationDeliveryMessage(status) {
  if (status?.emailVerified) return "이메일 인증이 완료되었습니다.";
  switch (status?.deliveryStatus) {
    case "QUEUED": case "SENDING": return "인증 메일을 발송 중입니다. 잠시 기다려 주세요.";
    case "SENT": return "가입하신 이메일로 인증 링크를 보냈습니다. 메일함과 스팸함을 확인해 주세요.";
    case "FAILED": return "인증 메일을 보내지 못했습니다. 가입 정보는 저장되어 있습니다. 잠시 후 다시 요청해 주세요.";
    default: return "이메일 인증이 필요합니다. 인증 메일을 요청해 주세요.";
  }
}
