import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import Icon from "../components/Icon.jsx";
import CredentialFields from "../components/CredentialFields.jsx";
import { credentialError } from "../authValidation.js";

const oauthMessages = {
  session: "계정 연결 인증 정보가 만료되었습니다. Discord 연결을 다시 시작해 주세요.",
  discord: "Discord 연결이 취소되었거나 완료되지 않았습니다. 다시 시도해 주세요.",
  DISCORD_ACCOUNT_CONFLICT: "이미 다른 GuildUp 계정에 연결된 Discord 계정입니다. 기존 계정으로 로그인해 주세요.",
  DISCORD_ALREADY_LINKED: "현재 GuildUp 계정에는 다른 Discord 계정이 이미 연결되어 있습니다.",
};

export default function AccountSettingsPage() {
  const [account, setAccount] = useState(null);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
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

  return <div className="public-page onboarding-page">
    <AppHeader actions onError={setMessage} />
    <main className="public-main communities-main account-main" aria-labelledby="account-title">
      <div className="onboarding-heading"><h1 id="account-title">로그인 및 계정</h1>
        <p>하나의 GuildUp 계정에 로그인 방법을 추가할 수 있습니다.</p></div>
      {loading && <p role="status">계정 정보를 불러오는 중입니다.</p>}
      {message && <p className="message" role="alert">{message}</p>}
      {success && <p className="auth-success" role="status">{success}</p>}
      {account && <>
        <p className="account-identity"><strong>{account.user.nickname}</strong>님의 계정</p>
        <section className="panel account-method" aria-labelledby="account-discord-title">
          <div className="account-method-heading"><h2 id="account-discord-title"><Icon name="discord" size={20} />Discord</h2>
            <span className="role-badge">{account.discordConnected ? "연결됨" : "연결되지 않음"}</span></div>
          <p>{account.discordConnected ? `@${account.discordUsername || "Discord 사용자"}` : "Discord 계정을 연결하면 Discord로도 로그인할 수 있습니다."}</p>
          <p className="auth-hint">개인 로그인 계정 연결입니다. 커뮤니티의 Discord 서버 연결은 커뮤니티 설정에서 관리합니다.</p>
          {!account.discordConnected && <button type="button" disabled={busy} onClick={connectDiscord}>
            {busy && !adding ? "연결 시작 중..." : "Discord 연결"}</button>}
        </section>
        <section className="panel account-method" aria-labelledby="account-email-title">
          <div className="account-method-heading"><h2 id="account-email-title">이메일 로그인</h2>
            <span className="role-badge">{account.email ? "등록됨" : "등록되지 않음"}</span></div>
          {account.email ? <><p className="account-email">{account.email}</p>
            <p className="auth-hint">{account.emailVerified ? "인증된 이메일입니다." : "이메일 소유 인증은 아직 제공되지 않습니다. 현재 이메일과 비밀번호로 로그인할 수 있습니다."}</p></>
            : <><p>현재 계정에 이메일과 비밀번호를 추가합니다. 새 계정을 만들지 않습니다.</p>
              {!adding && <button type="button" disabled={busy} onClick={() => setAdding(true)}>이메일 로그인 추가</button>}
              {adding && <form className="auth-form" onSubmit={addEmail} noValidate aria-busy={busy}>
                <CredentialFields signup values={values} disabled={busy}
                  onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))} />
                <button disabled={busy}>{busy ? "추가 중..." : "이메일 로그인 추가"}</button>
                <button className="secondary-button" type="button" disabled={busy} onClick={() => setAdding(false)}>취소</button>
              </form>}</>}
        </section>
        {account.memberLinkConflicts?.length > 0 && <aside className="message" role="status">
          <p>아래 커뮤니티에는 Discord 계정과 연결된 별도 클랜원 기록이 있습니다. 기존 클랜원 연결과 기록을 보존했으며 자동으로 합치지 않았습니다. 운영자에게 클랜원 연결 확인을 요청해 주세요.</p>
          <ul>{account.memberLinkConflicts.map((item) => <li key={item.communityId}>{item.communityName}</li>)}</ul>
        </aside>}
      </>}
      <AppLink className="onboarding-restart" href="/communities.html">내 커뮤니티로 돌아가기</AppLink>
    </main><SiteFooter />
  </div>;
}
