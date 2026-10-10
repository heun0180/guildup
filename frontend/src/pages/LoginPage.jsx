import { useEffect, useRef, useState } from "react";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import Icon from "../components/Icon.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import CredentialFields from "../components/CredentialFields.jsx";
import { credentialError } from "../authValidation.js";
import { api } from "../api/http.js";

export default function LoginPage({ signup = false }) {
  const [values, setValues] = useState({ email: "", password: "", passwordConfirmation: "", nickname: "" });
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const retryDeadline = useRef(0);
  const [retrySeconds, setRetrySeconds] = useState(0);
  const [message, setMessage] = useState(() => {
    if (new URLSearchParams(window.location.search).get("withdrawn") === "true") return "회원탈퇴가 완료되었습니다.";
    const oauthError = new URLSearchParams(window.location.search).get("oauthError");
    if (oauthError === "session") return "로그인 인증 정보가 만료되었습니다. Discord 로그인을 다시 시작해 주세요.";
    if (oauthError === "discord") return "Discord 로그인이 취소되었거나 완료되지 않았습니다. 다시 시도해 주세요.";
    return "";
  });

  useEffect(() => {
    const controller = new AbortController();
    api("/api/auth/me", { signal: controller.signal })
      .then(() => { if (!controller.signal.aborted) window.location.replace("/communities.html"); })
      .catch((error) => { if (!controller.signal.aborted && error.status !== 401) setMessage(error.message); });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!retrySeconds) return;
    const timer = window.setInterval(() => {
      setRetrySeconds(Math.max(0, Math.ceil((retryDeadline.current - Date.now()) / 1000)));
    }, 250);
    return () => window.clearInterval(timer);
  }, [retrySeconds > 0]);

  async function submit(event) {
    event.preventDefault();
    // The refs guard consecutive submits before React has rendered the disabled button.
    if (pending.current || Date.now() < retryDeadline.current) return;
    setMessage("");
    if (signup) {
      const error = credentialError(values);
      if (error) { setMessage(error); return; }
      if (!values.nickname.trim()) { setMessage("닉네임을 입력해 주세요."); return; }
    } else if (!values.email.trim() || !values.password) {
      setMessage("이메일과 비밀번호를 입력해 주세요."); return;
    }
    pending.current = true;
    setBusy(true);
    try {
      const body = signup ? values : { email: values.email, password: values.password };
      await api(signup ? "/api/auth/signup" : "/api/auth/login", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      });
      setValues({ email: "", password: "", passwordConfirmation: "", nickname: "" });
      window.location.replace(signup ? "/email-verification.html" : "/communities.html");
    } catch (error) {
      if (!signup && error.status === 429) {
        const seconds = error.retryAfterSeconds ?? 5;
        retryDeadline.current = Date.now() + seconds * 1000;
        setRetrySeconds(seconds);
        setMessage("로그인 시도가 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.");
      } else setMessage(error.message);
      setBusy(false);
    } finally { pending.current = false; }
  }

  return <div className="public-page login-page">
    <AppHeader />
    <main className="public-main login-main">
      <section className="login-card" aria-labelledby="login-title">
        <span className="login-brand-mark" aria-hidden="true">G</span>
        <p className="eyebrow">GuildUp</p>
        <h1 id="login-title">{signup ? "GuildUp 회원가입" : "로그인"}</h1>
        <p className="login-description">{signup ? "Discord 없이도 커뮤니티와 함께 시작하세요." : "이메일 또는 Discord 계정으로 로그인하세요."}</p>
        {message && <p className="message" role="alert">{message}</p>}
        {retrySeconds > 0 && <p className="login-description" role="status">{retrySeconds}초 후 다시 시도할 수 있습니다.</p>}
        <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
          <CredentialFields values={values} signup={signup} nickname={signup} disabled={busy}
            onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))} />
          <button disabled={busy || retrySeconds > 0}>{busy ? (signup ? "가입 중..." : "로그인 중...")
            : retrySeconds > 0 ? `다시 시도 (${retrySeconds}초)` : (signup ? "회원가입" : "로그인")}</button>
        </form>
        <div className="auth-divider">또는</div>
        <a className={`button-link login-button${busy ? " disabled-link" : ""}`} href="/api/auth/discord/authorize"
          aria-disabled={busy} onClick={(event) => { if (busy) event.preventDefault(); }}>
          <Icon name="discord" size={20} />디스코드 로그인
        </a>
        <p className="auth-links">{signup ? <>이미 계정이 있으신가요? <AppLink href="/login.html">로그인</AppLink></>
          : <AppLink href="/signup.html">회원가입</AppLink>}</p>
        {!signup && <p className="auth-links"><AppLink href="/forgot-password.html">비밀번호를 잊으셨나요?</AppLink></p>}
        {signup && <p className="auth-existing-account">이미 Discord로 GuildUp을 이용하고 계신가요?<br />
          Discord로 로그인한 뒤 <strong>계정</strong>에서 이메일 로그인을 추가해 주세요. 기존 기록을 그대로 사용할 수 있습니다.</p>}
      </section>
    </main><SiteFooter showAbout={false} />
  </div>;
}
