import { useEffect, useRef, useState } from "react";
import { api } from "../api/http.js";
import { verificationDeliveryMessage } from "../emailVerification.js";

export default function EmailVerificationPanel({ initialStatus, onVerified }) {
  const [status, setStatus] = useState(initialStatus);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const pending = useRef(false);
  const deadline = useRef(Date.now() + (initialStatus?.retryAfterSeconds || 0) * 1000);
  const [seconds, setSeconds] = useState(initialStatus?.retryAfterSeconds || 0);
  useEffect(() => {
    setStatus(initialStatus);
    deadline.current = Date.now() + (initialStatus?.retryAfterSeconds || 0) * 1000;
    setSeconds(initialStatus?.retryAfterSeconds || 0);
  }, [initialStatus]);
  useEffect(() => {
    if (!seconds) return;
    const timer = window.setInterval(() => setSeconds(Math.max(0, Math.ceil((deadline.current - Date.now()) / 1000))), 1000);
    return () => window.clearInterval(timer);
  }, [seconds > 0]);
  useEffect(() => {
    if (!["QUEUED", "SENDING"].includes(status?.deliveryStatus)) return;
    const controller = new AbortController();
    // Bound polling: account data survives an interrupted process and resend remains available.
    let attempts = 0;
    const timer = window.setInterval(async () => {
      if (++attempts > 10) { window.clearInterval(timer); return; }
      try {
        const updated = await api("/api/auth/email-verification", { signal: controller.signal });
        if (controller.signal.aborted) return;
        setStatus(updated);
        if (updated.emailVerified) onVerified?.();
      } catch (error) { if (!controller.signal.aborted) { setMessage(error.message); window.clearInterval(timer); } }
    }, 2000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [status?.deliveryStatus, onVerified]);
  async function resend() {
    if (pending.current || Date.now() < deadline.current) return;
    pending.current = true; setBusy(true); setMessage("");
    try {
      const updated = await api("/api/auth/email-verification/resend", { method: "POST" });
      setStatus(updated);
      deadline.current = Date.now() + updated.retryAfterSeconds * 1000;
      setSeconds(updated.retryAfterSeconds);
      if (updated.emailVerified) onVerified?.();
    } catch (error) {
      setMessage(error.message);
      if (error.status === 429) {
        const retry = error.retryAfterSeconds || 60;
        deadline.current = Date.now() + retry * 1000; setSeconds(retry);
      }
    } finally { pending.current = false; setBusy(false); }
  }
  async function refresh() {
    if (pending.current) return;
    pending.current = true; setBusy(true); setMessage("");
    try {
      const updated = await api("/api/auth/email-verification");
      setStatus(updated);
      deadline.current = Date.now() + updated.retryAfterSeconds * 1000;
      setSeconds(updated.retryAfterSeconds);
      if (updated.emailVerified) onVerified?.();
    } catch (error) { setMessage(error.message); }
    finally { pending.current = false; setBusy(false); }
  }
  return <div className="email-verification-panel" aria-busy={busy}>
    <p className="auth-hint" role="status">{verificationDeliveryMessage(status)}</p>
    {message && <p className="message" role="alert">{message}</p>}
    {!status?.emailVerified && <button type="button" className="secondary-button" disabled={busy || seconds > 0} onClick={resend}>
      {busy ? "발송 요청 중..." : seconds > 0 ? `다시 요청 (${seconds}초)` : "인증 메일 다시 보내기"}</button>}
    {!status?.emailVerified && <button type="button" className="text-button" disabled={busy} onClick={refresh}>인증 상태 확인</button>}
    <p className="auth-hint">새로 요청하면 이전 인증 링크는 사용할 수 없습니다. 가장 최근에 받은 메일을 열어 주세요.</p>
  </div>;
}
