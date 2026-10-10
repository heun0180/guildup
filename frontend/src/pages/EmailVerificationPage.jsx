import { useEffect, useRef, useState } from "react";
import { api } from "../api/http.js";
import { clearVerificationLinkToken, verificationLinkToken } from "../emailVerification.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import EmailVerificationPanel from "../components/EmailVerificationPanel.jsx";

export default function EmailVerificationPage() {
  const [token, setToken] = useState(verificationLinkToken);
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [loginRequired, setLoginRequired] = useState(false);
  const [message, setMessage] = useState("");
  const [success, setSuccess] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    api("/api/auth/email-verification", { signal: controller.signal }).then((value) => {
      if (controller.signal.aborted) return;
      setStatus(value); setSuccess(value.emailVerified);
    }).catch((error) => {
      if (controller.signal.aborted) return;
      if (error.status === 401) setLoginRequired(true);
      else setMessage(error.message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);
  async function confirm() {
    if (pending.current) return;
    pending.current = true; setBusy(true); setMessage("");
    try {
      await api("/api/auth/email-verification/confirm", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token }) });
      clearVerificationLinkToken(); setToken(""); setSuccess(true);
    } catch (error) {
      setMessage(error.message);
      if (error.status === 401) setLoginRequired(true);
    } finally { pending.current = false; setBusy(false); }
  }
  return <div className="public-page login-page">
    <AppHeader />
    <main className="public-main login-main">
      <section className="login-card verification-card" aria-labelledby="verification-title" aria-busy={loading || busy}>
        <span className="login-brand-mark" aria-hidden="true">G</span>
        <p className="eyebrow">GuildUp</p>
        <h1 id="verification-title">{success ? "이메일 인증이 완료되었습니다." : "이메일 인증이 필요합니다."}</h1>
        {loading && <p role="status">인증 상태를 확인하는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {success ? <><p className="auth-success" role="status">이제 GuildUp을 이용할 수 있습니다.</p>
          <AppLink className="button-link" href="/communities.html">내 커뮤니티로 이동</AppLink></>
          : !loading && <>
            {loginRequired ? <><p>가입한 계정으로 로그인한 뒤 메일의 인증 링크를 다시 열어 주세요. 로그인 세션이 만료된 경우에도 같은 방법으로 진행할 수 있습니다.</p>
              <AppLink className="button-link" href="/login.html">로그인</AppLink></>
              : status?.hasEmailCredential ? <>
                {token && <><p>가입한 계정의 이메일을 확인하려면 아래 버튼을 눌러 주세요.</p>
                  <button type="button" disabled={busy} onClick={confirm}>{busy ? "인증 처리 중..." : "이메일 인증 완료"}</button></>}
                <EmailVerificationPanel initialStatus={status} onVerified={() => setSuccess(true)} />
                <p className="login-description">인증 링크를 열고 확인 버튼을 누른 뒤 내 커뮤니티로 이동해 주세요. 다른 계정으로 로그인했다면 로그아웃 후 가입한 계정으로 다시 로그인해 주세요.</p>
              </> : status && <p>현재 계정에는 이메일 로그인 방법이 없습니다. 계정 설정에서 이메일 로그인을 추가할 수 있습니다. Discord 로그인은 계속 사용할 수 있습니다.</p>}
          </>}
        <p className="auth-links"><AppLink href="/account.html">계정 설정</AppLink> · <AppLink href="/login.html">로그인</AppLink></p>
      </section>
    </main><SiteFooter showAbout={false} />
  </div>;
}
