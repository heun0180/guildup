import { useEffect, useRef, useState } from "react";
import { api } from "../api/http.js";
import { clearResetLinkToken, resetLinkToken } from "../passwordReset.js";
import { passwordError } from "../authValidation.js";
import { PASSWORD_HINT } from "../components/CredentialFields.jsx";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";

export default function PasswordResetPage() {
  const [token, setToken] = useState(resetLinkToken);
  const [values, setValues] = useState({ password: "", passwordConfirmation: "" });
  const [visible, setVisible] = useState(false);
  const [checking, setChecking] = useState(true);
  const [usable, setUsable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState(false);
  const [message, setMessage] = useState("");
  const [linkRevision, setLinkRevision] = useState(0);
  const pending = useRef(false);
  const generation = useRef(0);
  const submission = useRef(null);
  useEffect(() => {
    // Opening another mail link in the same tab may be a fragment-only browser navigation.
    function receiveLink() {
      if (window.location.pathname !== "/password-reset.html" || !window.location.hash) return;
      const incoming = resetLinkToken();
      generation.current++; submission.current?.abort(); pending.current = false; setBusy(false);
      setToken(incoming); setValues({ password: "", passwordConfirmation: "" }); setVisible(false); setMessage("");
      setSuccess(false); setUsable(false); setChecking(true); setLinkRevision(value => value + 1);
    }
    window.addEventListener("hashchange", receiveLink); window.addEventListener("popstate", receiveLink);
    return () => {
      window.removeEventListener("hashchange", receiveLink); window.removeEventListener("popstate", receiveLink);
      generation.current++; submission.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (success) return;
    if (!token) { setChecking(false); setMessage(previous => previous || "재설정 링크를 사용할 수 없습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요."); return; }
    const controller = new AbortController();
    api("/api/auth/password-reset/validate", { method: "POST", cache: "no-store", signal: controller.signal,
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token }) })
      .then(() => { if (!controller.signal.aborted) setUsable(true); })
      .catch(error => { if (!controller.signal.aborted) setMessage(error.message); })
      .finally(() => { if (!controller.signal.aborted) setChecking(false); });
    return () => { controller.abort(); clearResetLinkToken(); };
  }, [token, success, linkRevision]);
  async function submit(event) {
    event.preventDefault();
    if (pending.current || !usable) return;
    const error = passwordError(values);
    if (error) { setMessage(error); return; }
    pending.current = true; setBusy(true); setMessage("");
    const started = generation.current;
    const controller = new AbortController(); submission.current = controller;
    try {
      await api("/api/auth/password-reset/confirm", { method: "POST", cache: "no-store", signal: controller.signal,
        headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token, ...values }) });
      if (controller.signal.aborted || started !== generation.current) return;
      clearResetLinkToken(); setToken(""); setValues({ password: "", passwordConfirmation: "" }); setSuccess(true); setUsable(false);
    } catch (failure) {
      if (controller.signal.aborted || started !== generation.current) return;
      setMessage(failure.message);
      if (["PASSWORD_RESET_INVALID", "PASSWORD_RESET_EXPIRED", "PASSWORD_RESET_REUSED"].includes(failure.code)) {
        setUsable(false); clearResetLinkToken(); setToken("");
      }
    } finally { if (started === generation.current) { pending.current = false; setBusy(false); submission.current = null; } }
  }
  return <div className="public-page login-page">
    <AppHeader />
    <main className="public-main login-main"><section className="login-card reset-card" aria-labelledby="reset-title" aria-busy={checking || busy}>
      <span className="login-brand-mark" aria-hidden="true">G</span><p className="eyebrow">GuildUp</p>
      <h1 id="reset-title">새 비밀번호 설정</h1>
      {success ? <><p className="auth-success" role="status">비밀번호가 변경되었습니다.<br />새로운 비밀번호로 로그인해 주세요.</p>
        <AppLink className="button-link" href="/login.html">로그인하러 가기</AppLink></> : <>
        {checking && <p role="status">재설정 링크를 확인하는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {!checking && usable && <form className="auth-form" onSubmit={submit}>
          {[["password", "새 비밀번호"], ["passwordConfirmation", "새 비밀번호 확인"]].map(([name, label]) =>
            <div className="auth-field" key={name}><label htmlFor={`reset-${name}`}>{label}</label>
              <input id={`reset-${name}`} name={name} type={visible ? "text" : "password"} autoComplete="new-password"
                maxLength={72} required disabled={busy} spellCheck={false} value={values[name]}
                aria-describedby={name === "password" ? "reset-password-hint" : "reset-password-match"}
                onChange={event => setValues(previous => ({ ...previous, [name]: event.target.value }))} />
              {name === "password" && <p id="reset-password-hint" className="auth-hint">{PASSWORD_HINT}</p>}
            </div>)}
          <button className="password-visibility" type="button" disabled={busy} aria-pressed={visible}
            onClick={() => setVisible(previous => !previous)}>{visible ? "비밀번호 숨기기" : "비밀번호 표시"}</button>
          <p id="reset-password-match" className="auth-hint" aria-live="polite">{values.passwordConfirmation
            ? values.password === values.passwordConfirmation ? "비밀번호가 일치합니다." : "비밀번호가 일치하지 않습니다." : "새 비밀번호를 다시 입력해 주세요."}</p>
          <button className="auth-submit" type="submit" disabled={busy}>{busy ? "변경 중..." : "비밀번호 변경하기"}</button>
        </form>}
        {!checking && !usable && <AppLink className="button-link" href="/forgot-password.html">비밀번호 찾기</AppLink>}
        <p className="auth-links"><AppLink href="/login.html">로그인</AppLink></p>
      </>}
    </section></main><SiteFooter showAbout={false} />
  </div>;
}
