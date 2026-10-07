import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppLink from "./AppLink.jsx";

export default function AccountWithdrawalPanel() {
  const returned = new URLSearchParams(window.location.search).get("withdrawalVerified") === "true";
  const [open, setOpen] = useState(returned);
  const [check, setCheck] = useState(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [password, setPassword] = useState("");
  const [step, setStep] = useState("notice");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    api("/api/account/withdrawal/check", { signal: controller.signal }).then(setCheck).catch((error) => {
      if (error.name !== "AbortError" && !redirectToLogin(error)) setMessage(error.message);
    });
    return () => controller.abort();
  }, [open]);

  function cancel() {
    setOpen(false); setCheck(null); setAcknowledged(false); setPassword(""); setStep("notice"); setMessage("");
  }

  function handleError(error) {
    // 잘못된 비밀번호는 로그인 세션을 종료하지 않는다.
    if (redirectToLogin(error)) return;
    setMessage(error.message);
    if (error.ownedCommunities?.length) setCheck((value) => ({ ...value, canWithdraw: false, ownedCommunities: error.ownedCommunities }));
    if (error.code === "WITHDRAWAL_VERIFICATION_REQUIRED") {
      setCheck((value) => ({ ...value, verified: false })); setStep("verify");
    }
  }

  async function verify(event) {
    event.preventDefault();
    if (busy || !password) return;
    setBusy(true); setMessage("");
    try {
      await api("/api/account/withdrawal/verify", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password }) });
      setCheck((value) => ({ ...value, verified: true })); setStep("confirm");
    } catch (error) { handleError(error); }
    finally { setPassword(""); setBusy(false); }
  }

  async function verifyDiscord() {
    if (busy) return;
    setBusy(true); setMessage("");
    try {
      const response = await api("/api/auth/discord/withdrawal", { method: "POST" });
      window.location.assign(response.authorizationUrl);
    } catch (error) { handleError(error); setBusy(false); }
  }

  async function withdraw() {
    if (busy || !acknowledged || !check?.verified || !check.canWithdraw || step !== "confirm") return;
    setBusy(true); setMessage("");
    try {
      await api("/api/account", { method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ acknowledged: true }) });
      window.location.replace("/login.html?withdrawn=true");
    } catch (error) { handleError(error); setBusy(false); }
  }

  return <section className="panel account-danger" aria-labelledby="withdrawal-title" aria-busy={busy}>
    <p className="danger-label">위험 영역</p><h2 id="withdrawal-title">회원탈퇴</h2>
    {!open ? <><p>GuildUp 계정을 탈퇴하고 로그인 정보를 삭제합니다. 탈퇴 후 계정을 복구할 수 없습니다.</p>
      <button type="button" className="danger-button" onClick={() => setOpen(true)}>회원탈퇴</button></>
      : <div className="withdrawal-flow">
        {message && <p className="message" role="alert">{message}</p>}
        {!check && !message && <p role="status">회원탈퇴 가능 여부를 확인하는 중입니다.</p>}
        {check?.ownedCommunities?.length > 0 ? <div role="alert">
          <p><strong>회원탈퇴를 진행할 수 없습니다.</strong></p><p>소유 중인 커뮤니티가 있습니다.</p>
          <ul>{check.ownedCommunities.map((item) => <li key={item.communityId}>
            <AppLink href={`/community-settings.html?communityId=${item.communityId}`}>{item.communityName}</AppLink>
          </li>)}</ul><p>소유권을 이전하거나 커뮤니티를 삭제한 후 다시 시도해주세요.</p>
        </div> : check && <>
          {step === "notice" && <>
            <p>회원탈퇴 시 GuildUp에 더 이상 로그인할 수 없습니다.</p>
            <ul><li>모든 GuildUp 커뮤니티 멤버십이 종료됩니다.</li><li>이메일 로그인 정보가 삭제됩니다.</li>
              <li>Discord 연결이 해제됩니다.</li><li>일부 게임·이벤트 기록은 결과 보존을 위해 익명화된 상태로 남을 수 있습니다.</li>
              <li>다시 가입하면 새 계정이 생성되며 과거 계정과 기록은 복구되지 않습니다.</li></ul>
            <label className="withdrawal-ack"><input type="checkbox" checked={acknowledged} disabled={busy}
              onChange={(event) => setAcknowledged(event.target.checked)} />위 내용을 확인했습니다.</label>
            <button type="button" disabled={!acknowledged || !check.canWithdraw || busy}
              onClick={() => setStep(check.verified ? "confirm" : "verify")}>다음</button>
            {!check.canWithdraw && <p role="alert">본인 확인 수단을 확인할 수 없습니다. 관리자에게 문의해 주세요.</p>}
          </>}
          {step === "verify" && (check.verificationMethod === "PASSWORD" ? <form className="auth-form" onSubmit={verify}>
            <label htmlFor="withdrawal-password">현재 비밀번호 확인</label>
            <input id="withdrawal-password" name="withdrawalPassword" type="password" autoComplete="current-password"
              value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} required autoFocus />
            <button disabled={busy || !password}>{busy ? "확인 중..." : "비밀번호 확인"}</button>
          </form> : <><p>본인 확인을 위해 연결된 Discord 계정으로 인증을 진행해주세요.</p>
            <button type="button" disabled={busy} onClick={verifyDiscord}>Discord로 본인 확인</button></>)}
          {step === "confirm" && <div role="group" aria-label="회원탈퇴 최종 확인">
            <h3>정말 회원탈퇴하시겠습니까?</h3><p>이 작업은 되돌릴 수 없습니다.</p>
            <button type="button" className="danger-button" disabled={busy || !acknowledged} onClick={withdraw}>
              {busy ? "탈퇴 처리 중..." : "회원탈퇴"}</button>
          </div>}
        </>}
        <button type="button" className="secondary-button" disabled={busy} onClick={cancel}>취소</button>
      </div>}
  </section>;
}
