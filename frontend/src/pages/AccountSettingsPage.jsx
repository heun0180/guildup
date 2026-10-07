import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import Icon from "../components/Icon.jsx";
import CredentialFields from "../components/CredentialFields.jsx";
import AccountWithdrawalPanel from "../components/AccountWithdrawalPanel.jsx";
import AccountProfilePanel from "../components/AccountProfilePanel.jsx";
import Avatar from "../components/Avatar.jsx";
import { credentialError } from "../authValidation.js";

const oauthMessages = {
  session: "Discord 인증 정보가 만료되었습니다. 인증을 다시 시작해 주세요.",
  discord: "Discord 인증이 취소되었거나 완료되지 않았습니다. 다시 시도해 주세요.",
  DISCORD_ACCOUNT_CONFLICT: "이미 다른 GuildUp 계정에 연결된 Discord 계정입니다. 기존 계정으로 로그인해 주세요.",
  DISCORD_ALREADY_LINKED: "현재 GuildUp 계정에는 다른 Discord 계정이 이미 연결되어 있습니다.",
  DISCORD_IDENTITY_MISMATCH: "현재 계정에 연결된 Discord 계정으로 본인 확인을 진행해 주세요.",
  WITHDRAWAL_VERIFICATION_REQUIRED: "회원탈퇴를 위해 본인 확인을 다시 진행해 주세요.",
};

export default function AccountSettingsPage() {
  const [account, setAccount] = useState(null);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [values, setValues] = useState({ email: "", password: "", passwordConfirmation: "" });
  const [message, setMessage] = useState(() => oauthMessages[new URLSearchParams(window.location.search).get("oauthError")] || "");
  const [success, setSuccess] = useState(() => new URLSearchParams(window.location.search).get("discordLinked") === "true"
    ? "Discord 계정을 연결했습니다. 기존 GuildUp 계정으로 계속 이용할 수 있습니다." : "");

  useEffect(() => {
    const controller = new AbortController();
    api("/api/auth/account", { signal: controller.signal }).then(setAccount).catch((error) => {
      if (error.name !== "AbortError" && !redirectToLogin(error)) setMessage(error.message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);

  async function addEmail(event) {
    event.preventDefault();
    if (busy) return;
    const error = credentialError(values);
    if (error) { setMessage(error); return; }
    setBusy(true); setMessage(""); setSuccess("");
    try {
      await api("/api/auth/credentials", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(values) });
      setValues({ email: "", password: "", passwordConfirmation: "" });
      setAdding(false);
      setAccount(await api("/api/auth/account"));
      setSuccess("이메일 로그인을 추가했습니다. 현재 계정과 커뮤니티 기록은 그대로 유지됩니다.");
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message); }
    finally { setBusy(false); }
  }

  async function connectDiscord() {
    if (busy) return;
    setBusy(true); setMessage(""); setSuccess("");
    try {
      const started = await api("/api/auth/discord/link", { method: "POST" });
      window.location.assign(started.authorizationUrl);
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message); setBusy(false); }
  }

  async function disconnectDiscord() {
    if (busy || !account.canDisconnectDiscord) return;
    setBusy(true); setMessage(""); setSuccess("");
    try {
      await api("/api/account/connections/discord", { method: "DELETE" });
      // 해제 자체는 이미 완료되었으므로 후속 조회가 실패해도 연결 상태를 즉시 반영한다.
      setAccount((current) => ({ ...current, discordConnected: false, discordUsername: null,
        discordDisplayName: null, discordAvatarUrl: null, canDisconnectDiscord: false, memberLinkConflicts: [] }));
      setDisconnecting(false);
      setSuccess("Discord 연결을 해제했습니다. 이메일로 계속 로그인할 수 있습니다.");
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message); }
    finally { setBusy(false); }
  }

  const joinedAt = account?.createdAt ? new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric",
    month: "2-digit", day: "2-digit" }).format(new Date(account.createdAt)) : null;

  return <div className="public-page onboarding-page">
    <AppHeader actions onError={setMessage} currentUser={account?.user} />
    <main className="public-main account-main" aria-labelledby="account-title">
      <div className="onboarding-heading"><h1 id="account-title">계정</h1>
        <p>프로필, 로그인 방법과 연결된 계정을 관리합니다.</p></div>
      {loading && <p role="status">계정 정보를 불러오는 중입니다.</p>}
      {message && <p className="message" role="alert">{message}</p>}
      {success && <p className="auth-success" role="status">{success}</p>}
      {account && <>
        <AccountProfilePanel discordConnected={account.discordConnected} onSaved={(profile) => {
          setAccount((current) => ({ ...current, user: { ...current.user, nickname: profile.nickname } }));
        }} />
        <section className="panel account-section" aria-labelledby="account-security-title">
          <div className="account-section-heading"><h2 id="account-security-title">로그인 및 보안</h2>
            <p>이 계정으로 로그인할 수 있는 방법을 확인합니다.</p></div>
          <div className="account-method-heading"><h3 id="account-email-title">이메일 로그인</h3>
            <span className="role-badge">{account.email ? "등록됨" : "등록되지 않음"}</span></div>
          {account.email ? <><dl className="account-details"><div><dt>이메일</dt><dd className="account-email">{account.email}</dd></div>
            <div><dt>인증 상태</dt><dd>{account.emailVerified ? "인증됨" : "미인증"}</dd></div>
            <div><dt>비밀번호</dt><dd aria-label="비밀번호가 설정되어 있습니다">••••••••</dd></div></dl>
            <p className="auth-hint">{account.emailVerified ? "인증된 이메일입니다." : "이메일 소유 인증은 아직 제공되지 않습니다. 현재 이메일과 비밀번호로 로그인할 수 있습니다."}</p>
            <p className="auth-hint">비밀번호 변경 기능은 추후 제공됩니다.</p></>
            : <><p>현재 계정에 이메일과 비밀번호를 추가합니다. 새 계정을 만들지 않습니다.</p>
              {!adding && <button type="button" disabled={busy} onClick={() => setAdding(true)}>이메일 로그인 추가</button>}
              {adding && <form className="auth-form" onSubmit={addEmail} noValidate aria-busy={busy}>
                <CredentialFields signup values={values} disabled={busy}
                  onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))} />
                <button disabled={busy}>{busy ? "추가 중..." : "이메일 로그인 추가"}</button>
                <button className="secondary-button" type="button" disabled={busy} onClick={() => setAdding(false)}>취소</button>
              </form>}</>}
        </section>
        <section className="panel account-section" aria-labelledby="account-connections-title">
          <div className="account-section-heading"><h2 id="account-connections-title">연결된 계정</h2>
            <p>개인 외부 계정을 연결하고 관리합니다.</p></div>
          <div className="account-method-heading"><h3 id="account-discord-title"><Icon name="discord" size={20} />Discord</h3>
            <span className="role-badge">{account.discordConnected ? "연결됨" : "연결되지 않음"}</span></div>
          {account.discordConnected ? <>
            <div className="account-discord-identity"><Avatar src={account.discordAvatarUrl} name={account.discordDisplayName || account.discordUsername || "D"} />
              <div><strong>{account.discordDisplayName || account.discordUsername || "Discord 사용자"}</strong>
                {account.discordUsername && <p>@{account.discordUsername}</p>}</div></div>
            <p className="auth-hint">Discord에서 사용하는 이름입니다. GuildUp 닉네임과 별도로 관리합니다.</p>
            {!disconnecting ? <button className="secondary-button" type="button" disabled={busy || !account.canDisconnectDiscord}
              onClick={() => setDisconnecting(true)}>연결 해제</button>
              : <div className="account-disconnect-confirm" role="group" aria-label="Discord 연결 해제 확인">
                <p>Discord 연결을 해제하면 Discord로 로그인할 수 없습니다. 이후 <strong>{account.email}</strong> 이메일과 비밀번호로 로그인해 주세요.</p>
                <div className="account-inline-actions"><button className="secondary-button" type="button" disabled={busy} onClick={disconnectDiscord}>
                  {busy ? "해제 중..." : "Discord 연결 해제"}</button>
                  <button className="text-button" type="button" disabled={busy} onClick={() => setDisconnecting(false)}>취소</button></div></div>}
            {!account.canDisconnectDiscord && <p className="auth-hint">로그인 방법을 유지하려면 먼저 이메일 로그인을 추가해야 연결을 해제할 수 있습니다.</p>}
          </> : <><p>Discord 계정을 연결하면 Discord로도 로그인할 수 있습니다.</p>
            <button type="button" disabled={busy} onClick={connectDiscord}>{busy ? "연결 시작 중..." : "Discord 연결"}</button></>}
          <p className="auth-hint">개인 로그인 계정 연결입니다. 커뮤니티의 Discord 서버 연결은 커뮤니티 설정에서 관리합니다.</p>
          {account.memberLinkConflicts?.length > 0 && <aside className="message" role="status">
            <p>아래 커뮤니티에는 Discord 계정과 연결된 별도 클랜원 기록이 있습니다. 기존 클랜원 연결과 기록을 보존했으며 자동으로 합치지 않았습니다. 운영자에게 클랜원 연결 확인을 요청해 주세요.</p>
            <ul>{account.memberLinkConflicts.map((item) => <li key={item.communityId}>{item.communityName}</li>)}</ul>
          </aside>}
        </section>
        <section className="panel account-section account-management" aria-labelledby="account-management-title">
          <div className="account-section-heading"><h2 id="account-management-title">계정 관리</h2></div>
          {joinedAt && <dl className="account-details"><div><dt>가입일</dt><dd><time dateTime={account.createdAt}>{joinedAt}</time></dd></div></dl>}
          <AccountWithdrawalPanel />
        </section>
      </>}
      <AppLink className="onboarding-restart" href="/communities.html">내 커뮤니티로 돌아가기</AppLink>
    </main><SiteFooter />
  </div>;
}
