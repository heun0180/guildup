import { useRef, useState } from "react";
import { api } from "../api/http.js";
import { RESET_ACCEPTED_MESSAGE } from "../passwordReset.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [message, setMessage] = useState("");
  const pending = useRef(false);
  async function submit(event) {
    event.preventDefault();
    if (pending.current) return;
    if (!email.trim()) { setMessage("이메일 주소를 입력해 주세요."); return; }
    pending.current = true; setBusy(true); setMessage("");
    try {
      await api("/api/auth/password-reset/request", { method: "POST", cache: "no-store",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email }) });
      setEmail(""); setSent(true);
    } catch (error) { setMessage(error.message); }
    finally { pending.current = false; setBusy(false); }
  }
  return <div className="public-page login-page">
    <AppHeader />
    <main className="public-main login-main"><section className="login-card reset-card" aria-labelledby="forgot-title" aria-busy={busy}>
      <span className="login-brand-mark" aria-hidden="true">G</span><p className="eyebrow">GuildUp</p>
      <h1 id="forgot-title">비밀번호 찾기</h1>
      {sent ? <p className="auth-success" role="status">{RESET_ACCEPTED_MESSAGE}</p> : <>
        <p className="login-description">가입하신 이메일 주소를 입력하면<br />비밀번호 재설정 링크를 보내드립니다.</p>
        {message && <p className="message" role="alert">{message}</p>}
        <form className="auth-form" onSubmit={submit}>
          <div className="auth-field"><label htmlFor="reset-email">이메일 주소</label>
            <input id="reset-email" name="email" type="email" autoComplete="email" autoCapitalize="none" spellCheck={false}
              maxLength={254} required disabled={busy} value={email} onChange={event => setEmail(event.target.value)} /></div>
          <button className="auth-submit" disabled={busy} type="submit">{busy ? "요청 중..." : "재설정 이메일 보내기"}</button>
        </form></>}
      <p className="auth-links"><AppLink href="/login.html">로그인하러 가기</AppLink></p>
    </section></main><SiteFooter showAbout={false} />
  </div>;
}
