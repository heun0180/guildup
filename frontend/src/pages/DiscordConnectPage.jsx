import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError, redirectToLogin } from "../api/http.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";

const installMessages = {
  400: "설치 요청 정보가 올바르지 않습니다. Discord 인증 다시 시작을 눌러주세요.",
  403: "이 커뮤니티에 접근할 권한이 없습니다.",
  404: "Discord 인증 결과 또는 설치 정보가 만료되었습니다. Discord 인증 다시 시작을 눌러주세요.",
  409: "Discord 연결 상태가 충돌합니다. 커뮤니티 대시보드에서 연결 상태를 확인해 주세요.",
  503: "Discord 설정이 완료되지 않았습니다. 서버 설정을 확인해 주세요.",
};

function installError(error) {
  if (error.code === "DISCORD_GUILD_ALREADY_CONNECTED") return error.communityName
    ? `이미 GuildUp에 등록된 Discord 서버입니다. 해당 Discord 서버는 '${error.communityName}' 커뮤니티에 연결되어 있습니다.`
    : error.message;
  if (error instanceof ApiError && error.status === 409) return error.message;
  if (error instanceof ApiError) return installMessages[error.status] || error.message;
  return error.message || "요청을 처리하지 못했습니다.";
}

export default function DiscordConnectPage() {
  const navigate = useNavigate();
  const { refreshCommunity } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  return <DashboardLayout active="roles" communityId={communityId}>
    <div className="dashboard-content narrow-content">
      <div className="page-heading is-compact"><p className="eyebrow">Discord</p><h1>Discord 연결</h1></div>
      <DiscordConnectionFlow communityId={communityId} onConnected={async () => {
        await refreshCommunity();
        navigate(`/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`, { replace: true });
      }} />
    </div>
  </DashboardLayout>;
}

/** 생성 전 설치 검증과 생성 후 서버 연결이 공유하는 OAuth/봇 설치 화면. */
export function DiscordConnectionFlow({ communityId, creation = false, onConnected, onBack, onLater }) {
  const navigate = useNavigate();
  // 주소에서 oauthResult를 지운 뒤에도 봇 설치 요청까지 메모리에 유지한다.
  const [oauthResult] = useState(() => new URLSearchParams(window.location.search).get("oauthResult"));
  const validId = creation || /^\d+$/.test(communityId ?? "");
  const [step, setStep] = useState(oauthResult ? "loading" : "start");
  const [result, setResult] = useState(null);
  const [selectedGuild, setSelectedGuild] = useState(null);
  const [installToken, setInstallToken] = useState(null);
  const [authorizationUrl, setAuthorizationUrl] = useState(null);
  const [existingCommunity, setExistingCommunity] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(validId ? "" : "주소에 올바른 communityId를 입력해 주세요.");

  const base = creation ? "/api/community-creation/discord" : `/api/communities/${encodeURIComponent(communityId)}/discord`;
  const oauthUrl = validId
    ? `${base}/oauth/authorize`
    : "#";

  useEffect(() => {
    if (!validId || !oauthResult) return;
    api(`${base}/oauth/results/${encodeURIComponent(oauthResult)}`)
      .then((data) => {
        setResult(data);
        setStep("guilds");
        navigate(creation ? "/community-create.html" : `/discord-connect.html?communityId=${encodeURIComponent(communityId)}`, { replace: true });
      })
      .catch((error) => {
        if (!redirectToLogin(error)) {
          setMessage("Discord 인증 결과가 만료되었거나 올바르지 않습니다. 다시 연결해 주세요.");
          setStep("start");
        }
      });
  }, [communityId, oauthResult, validId, base, creation, navigate]);

  async function selectGuild(guild) {
    setSelectedGuild(guild);
    setMessage("");
    setBusy(true);
    try {
      const data = await api(`${base}/guild-selection/inspect`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ oauthResultId: oauthResult, guildId: guild.id }),
      });
      if (data.alreadyConnected) {
        if (creation) {
          setMessage(`이미 GuildUp에 등록된 Discord 서버입니다. 해당 Discord 서버는 '${data.communityName}' 커뮤니티에 연결되어 있습니다.`);
          setStep("guilds");
        } else {
          setExistingCommunity(data);
          setStep("existing");
        }
      } else {
        setStep("install");
      }
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(installError(error));
    } finally {
      setBusy(false);
    }
  }

  async function joinExistingCommunity() {
    if (!selectedGuild || !oauthResult) return;
    setBusy(true);
    setMessage("");
    try {
      const joined = await api(`/api/communities/${encodeURIComponent(communityId)}/discord/guild-selection/join`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          oauthResultId: oauthResult,
          guildId: selectedGuild.id,
          discardSourceCommunity: false,
        }),
      });
      navigate(`/community-dashboard.html?communityId=${encodeURIComponent(joined.communityId)}`, { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.code === "ALREADY_COMMUNITY_MEMBER" && error.communityId) {
        navigate(`/community-dashboard.html?communityId=${encodeURIComponent(error.communityId)}`, { replace: true });
      } else if (!redirectToLogin(error)) {
        setMessage(installError(error));
      }
    } finally {
      setBusy(false);
    }
  }

  async function startInstallation() {
    if (authorizationUrl) {
      window.open(authorizationUrl, "_blank", "noopener");
      return;
    }
    if (!selectedGuild || !oauthResult) return;

    const installWindow = window.open("about:blank", "_blank");
    setBusy(true);
    setMessage("");
    try {
      const data = await api(`${base}/bot-install/authorize`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ oauthResultId: oauthResult, guildId: selectedGuild.id }),
      });
      if (data.alreadyInstalled) {
        installWindow?.close();
        await onConnected(creation ? { installToken: data.installToken, guildName: data.guildName } : null);
        return;
      }
      setInstallToken(data.installToken);
      setAuthorizationUrl(data.authorizationUrl);
      if (installWindow) {
        installWindow.opener = null;
        installWindow.location.href = data.authorizationUrl;
      } else {
        setMessage("팝업이 차단되었습니다. 설치 페이지 다시 열기를 눌러주세요.");
      }
    } catch (error) {
      installWindow?.close();
      if (!redirectToLogin(error)) setMessage(installError(error));
    } finally {
      setBusy(false);
    }
  }

  async function confirmInstallation() {
    if (!installToken) return;
    setBusy(true);
    setMessage("");
    try {
      const confirmed = await api(creation ? `${base}/bot-install/confirm` : "/api/discord/bot-install/confirm", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ installToken }),
      });
      await onConnected(creation ? { installToken, guildName: confirmed.guildName } : null);
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(installError(error));
    } finally {
      setBusy(false);
    }
  }

  if (creation) return <DiscordCreationView step={step} result={result} selectedGuild={selectedGuild}
    installToken={installToken} authorizationUrl={authorizationUrl} oauthUrl={oauthUrl} busy={busy} message={message}
    onSelect={selectGuild} onInstall={startInstallation} onConfirm={confirmInstallation} onBack={onBack} onLater={onLater} />;

  return (
        <section className="panel connect-panel" aria-labelledby="page-title">

          {(step === "start" || step === "loading") && (
            <div className="connect-intro">
              <span className="connect-icon"><Icon name="discord" size={26} /></span>
              <div><h2>Discord 서버 연결</h2><p>관리 권한이 있는 Discord 서버를 선택하고 GuildUp 봇을 추가하세요.</p></div>
              <a className={`button-link connect-button${!validId ? " disabled-link" : ""}`} href={oauthUrl}
                 aria-disabled={!validId} onClick={(event) => !validId && event.preventDefault()}>
                {step === "loading" ? "인증 결과 확인 중..." : creation ? "Discord 서버 연결하기" : "Discord 연결하기"}
              </a>
            </div>
          )}

          {step === "guilds" && result && (
            <div>
              <div className="panel-section-heading"><span>1</span><div><h2>로그인 계정</h2><p>Discord 인증에 사용한 계정입니다.</p></div></div>
              <div className="account">
                <Avatar src={result.user.avatarUrl} name={result.user.globalName || result.user.username} className="avatar" />
                <div><div className="account-name">{result.user.globalName || result.user.username}</div><p>@{result.user.username}</p></div>
              </div>
              <div className="panel-section-heading"><span>2</span><div><h2>연결할 서버 선택</h2><p>소유자 또는 관리자인 서버만 표시됩니다.</p></div></div>
              <div className="guild-list">
                {result.guilds.map((guild) => (
                  <div className="guild" key={guild.id}>
                    <Avatar src={guild.iconUrl} name={guild.name} className="guild-icon" />
                    <div className="guild-info"><div className="guild-name">{guild.name}</div><p>{guild.owner ? "서버 소유자" : "서버 관리자"}</p></div>
                    <button type="button" disabled={busy} onClick={() => selectGuild(guild)}>
                      {busy && selectedGuild?.id === guild.id ? "확인 중..." : "선택"}
                    </button>
                  </div>
                ))}
              </div>
              {result.guilds.length === 0 && <p>관리할 수 있는 Discord 서버가 없습니다.</p>}
            </div>
          )}

          {step === "install" && selectedGuild && (
            <div className="install-view">
              <span className="connect-icon"><Icon name="discord" size={26} /></span>
              <h2>선택한 서버</h2>
              <div className="selected-guild"><Avatar src={selectedGuild.iconUrl} name={selectedGuild.name} className="guild-icon" /><div className="guild-name">{selectedGuild.name}</div></div>
              <p>{creation ? "봇 설치를 확인한 뒤 최종 생성 단계로 이동합니다." : "봇이 이미 있으면 바로 연결하고, 없으면 봇 설치 화면을 엽니다."}</p>
              <button type="button" disabled={busy} onClick={startInstallation}>
                {busy ? "확인 중..." : authorizationUrl ? "설치 페이지 다시 열기" : "GuildUp 봇 추가하기"}
              </button>
              <p><a href={oauthUrl}>Discord 인증 다시 시작</a></p>
              {installToken && (
                <div className="confirm-install">
                  <p>Bot 설치가 완료되었다면 아래 버튼을 눌러주세요.</p>
                  <button type="button" disabled={busy} onClick={confirmInstallation}>{busy ? "설치 확인 중..." : "설치 확인"}</button>
                </div>
              )}
            </div>
          )}

          {step === "existing" && selectedGuild && existingCommunity && (
            <div className="existing-community-view">
              <span className="connect-icon"><Icon name="discord" size={26} /></span>
              <h2>{existingCommunity.communityName}</h2>
              {existingCommunity.alreadyMember ? (
                <>
                  <p>이미 참여 중인 커뮤니티입니다.</p>
                  <button type="button" disabled={busy} onClick={joinExistingCommunity}>
                    커뮤니티로 이동
                  </button>
                </>
              ) : (
                <>
                  <p>이미 GuildUp에 등록된 Discord 서버입니다.</p>
                  <p>기존 커뮤니티에 참여하면 현재 운영진과 함께 이 커뮤니티를 관리할 수 있습니다.</p>
                  <button type="button" disabled={busy} onClick={joinExistingCommunity}>
                    {busy ? "참여 중..." : "기존 커뮤니티 참여"}
                  </button>
                  <small>이 커뮤니티의 설정은 그대로 유지됩니다.</small>
                </>
              )}
              <p><a href={oauthUrl}>다른 Discord 서버 선택</a></p>
            </div>
          )}
          {message && <p className="message" role="alert">{message}</p>}
        </section>
  );
}

/** 생성 화면의 표현만 분리한다. OAuth 및 설치 요청은 위 공유 흐름을 그대로 사용한다. */
function DiscordCreationView({ step, result, selectedGuild, installToken, authorizationUrl, oauthUrl,
  busy, message, onSelect, onInstall, onConfirm, onBack, onLater }) {
  const [startingOAuth, setStartingOAuth] = useState(false);
  const stageHeading = useRef(null);
  const choosing = step === "start" || step === "loading";
  const checking = step === "loading";

  useEffect(() => {
    // Browser back can restore the pre-OAuth page from the back/forward cache.
    const resetLoading = () => setStartingOAuth(false);
    window.addEventListener("pageshow", resetLoading);
    return () => window.removeEventListener("pageshow", resetLoading);
  }, []);

  useEffect(() => {
    stageHeading.current?.focus({ preventScroll: true });
    if (stageHeading.current && window.scrollY > 0) window.scrollTo(0, 0);
  }, [step]);

  function startOAuth(event) {
    if (startingOAuth || checking) event.preventDefault();
    else setStartingOAuth(true);
  }

  return <div className="onboarding-discord-flow" aria-busy={busy || checking || startingOAuth}>
    {choosing && <div className="onboarding-choices">
      <article className="onboarding-choice is-recommended">
        <div className="onboarding-choice-top"><Icon name="discord" size={24} /><span className="onboarding-recommendation">추천</span></div>
        <h2>Discord 연결하기</h2>
        <ul><li>서버 멤버 동기화</li><li>역할 연동</li><li>Discord 관련 관리 기능</li></ul>
        <a className={`button-link${startingOAuth || checking ? " disabled-link" : ""}`} href={oauthUrl}
          aria-disabled={startingOAuth || checking} onClick={startOAuth}>
          {checking ? "인증 결과 확인 중..." : startingOAuth ? "Discord로 이동 중..." : "Discord 연결하기 →"}
        </a>
      </article>
      <article className="onboarding-choice is-later">
        <h2>지금은 연결하지 않을게요</h2>
        <p>GuildUp은 Discord 없이도 사용할 수 있습니다.</p>
        <p>나중에 커뮤니티 설정에서 언제든 연결할 수 있어요.</p>
        <button className="secondary-button" type="button" disabled={checking || startingOAuth} onClick={onLater}>나중에 연결하기</button>
      </article>
    </div>}
    {step === "guilds" && result && <div className="onboarding-server-selection">
      <div className="onboarding-account"><Avatar src={result.user.avatarUrl} name={result.user.globalName || result.user.username} className="avatar" />
        <p><strong>{result.user.globalName || result.user.username}</strong> 계정으로 연결했어요.</p>
      </div>
      <h2 className="onboarding-stage-title" ref={stageHeading} tabIndex={-1}>어떤 서버를 연결할까요?</h2><p>소유자 또는 관리자인 서버만 표시됩니다.</p>
      <div className="onboarding-guilds">
        {result.guilds.map((guild) => <div className="onboarding-guild" key={guild.id}>
          <Avatar src={guild.iconUrl} name={guild.name} className="guild-icon" />
          <div className="guild-info"><div className="guild-name">{guild.name}</div><p>{guild.owner ? "서버 소유자" : "서버 관리자"}</p></div>
          <button className="secondary-button" type="button" disabled={busy} onClick={() => onSelect(guild)}>
            {busy && selectedGuild?.id === guild.id ? "확인 중..." : "선택"}
          </button>
        </div>)}
      </div>
      {result.guilds.length === 0 && <p className="onboarding-empty">관리할 수 있는 Discord 서버가 없습니다.</p>}
    </div>}
    {step === "install" && selectedGuild && <div className="onboarding-install">
      <div className="onboarding-selected-guild"><Avatar src={selectedGuild.iconUrl} name={selectedGuild.name} className="guild-icon" />
        <div><p>연결할 서버</p><h2>{selectedGuild.name}</h2></div>
      </div>
      <h2 className="onboarding-stage-title" ref={stageHeading} tabIndex={-1}>{installToken ? "봇 설치를 완료했나요?" : "GuildUp 봇을 추가해주세요"}</h2>
      <p>{installToken ? "Discord에서 설치를 마친 후 확인해주세요." : "봇 설치가 확인되면 마지막 확인 단계로 이동해요."}</p>
      {installToken ? <>
        <button type="button" disabled={busy} onClick={onConfirm}>{busy ? "설치 확인 중..." : "설치 확인"}</button>
        <button className="text-button" type="button" disabled={busy} onClick={onInstall}>설치 페이지 다시 열기</button>
      </> : <button type="button" disabled={busy} onClick={onInstall}>{busy ? "확인 중..." : "GuildUp 봇 추가하기"}</button>}
      <a className="onboarding-restart" href={oauthUrl}>Discord 인증 다시 시작</a>
    </div>}
    {message && <p className="message" role="alert">{message}</p>}
    <div className="creation-actions">
      <button className="text-button" type="button" disabled={busy || checking || startingOAuth} onClick={onBack}>← 이전</button>
      {!choosing && <button className="text-button" type="button" disabled={busy} onClick={onLater}>나중에 연결하기</button>}
    </div>
  </div>;
}
