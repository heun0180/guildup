import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import { DiscordConnectionFlow } from "./DiscordConnectPage.jsx";

const games = { BATTLEGROUNDS_KAKAO: "배틀그라운드 카카오", BATTLEGROUNDS_STEAM: "배틀그라운드 스팀" };
const newDraft = () => ({ name: "", gameType: "BATTLEGROUNDS_KAKAO", step: 1,
  discord: null, connecting: false, requestId: crypto.randomUUID(), pending: false });

export default function CommunityCreatePage() {
  const navigate = useNavigate();
  const [draft, setDraft] = useState(null);
  const [storageKey, setStorageKey] = useState(null);
  const [message, setMessage] = useState("");
  const [creating, setCreating] = useState(false);
  const submitting = useRef(false);
  const heading = useRef(null);

  useEffect(() => {
    if (!draft) return;
    heading.current?.focus({ preventScroll: true });
    if (window.scrollY > 0) window.scrollTo(0, 0);
  }, [draft?.step]);

  useEffect(() => {
    let cancelled = false;
    api("/api/auth/me").then((user) => {
      if (cancelled) return;
      const key = `guildup.community-creation.${user.id}`;
      let saved;
      try { saved = JSON.parse(sessionStorage.getItem(key)); } catch { /* 빈 초안으로 시작 */ }
      const initial = saved && games[saved.gameType] && typeof saved.name === "string"
        && typeof saved.requestId === "string" && [1, 2, 3].includes(saved.step) ? saved : newDraft();
      const params = new URLSearchParams(window.location.search);
      if (params.has("oauthResult") || params.has("oauthError")) {
        initial.step = 2;
        initial.connecting = params.has("oauthResult");
        if (params.has("oauthError")) setMessage("Discord 인증이 취소되었거나 완료되지 않았습니다. 다시 연결하거나 나중에 연결할 수 있습니다.");
      }
      setStorageKey(key);
      setDraft(initial);
    }).catch((error) => { if (!cancelled && !redirectToLogin(error)) setMessage(error.message); });
    return () => { cancelled = true; };
  }, []);

  function update(changes) {
    const next = { ...draft, ...changes };
    // OAuth 리다이렉트 전에 동기적으로 저장한다. 서버에는 입력값을 저장하지 않는다.
    sessionStorage.setItem(storageKey, JSON.stringify(next));
    setDraft(next);
    setMessage("");
  }

  function next(event) {
    event.preventDefault();
    if (!draft.name.trim()) return setMessage("커뮤니티 이름을 입력해 주세요.");
    update({ name: draft.name.trim(), step: 2 });
  }

  async function createCommunity() {
    if (submitting.current) return;
    submitting.current = true;
    setCreating(true);
    update({ pending: true });
    try {
      const created = await api("/api/communities", {
        method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": draft.requestId },
        body: JSON.stringify({ name: draft.name.trim(), gameType: draft.gameType,
          discordInstallToken: draft.discord?.installToken ?? null }),
      });
      sessionStorage.removeItem(storageKey);
      navigate(`/community-dashboard.html?communityId=${encodeURIComponent(created.id)}`, { replace: true });
    } catch (error) {
      if (error.status >= 400 && error.status < 500) update({ pending: false });
      if (!redirectToLogin(error)) setMessage(error.communityName
        ? `이미 GuildUp에 등록된 Discord 서버입니다. 해당 Discord 서버는 '${error.communityName}' 커뮤니티에 연결되어 있습니다.`
        : error.message);
    } finally { submitting.current = false; setCreating(false); }
  }

  return <div className="public-page onboarding-page">
    <AppHeader actions onError={setMessage} />
    <main className="public-main community-create-main" aria-labelledby="creation-title">
      {!draft && <div className="onboarding-heading"><h1 id="creation-title">새로운 커뮤니티를 만들어볼까요?</h1><p role="status">생성 정보를 준비하는 중입니다.</p></div>}
      {draft && <>
        <ol className="creation-steps" aria-label="커뮤니티 생성 단계">
          {["기본 정보", "Discord", "확인"].map((label, index) => <li key={label}
            aria-current={draft.step === index + 1 ? "step" : undefined}><span>{label}</span></li>)}
        </ol>
        {draft.step === 1 && <form className="onboarding-form" onSubmit={next}>
          <div className="onboarding-heading">
            <h1 id="creation-title" ref={heading} tabIndex={-1}>새로운 커뮤니티를 만들어볼까요?</h1>
            <p>운영하고 있는 클랜이나 게임 커뮤니티의 기본 정보를 입력해주세요.</p>
          </div>
          <div className="onboarding-field">
            <label htmlFor="community-name">커뮤니티 이름</label>
            <input id="community-name" required maxLength={255} value={draft.name} placeholder="예: 치즈 클랜"
              onChange={(event) => update({ name: event.target.value, requestId: crypto.randomUUID() })} />
          </div>
          <div className="onboarding-field">
            <label htmlFor="community-game">게임 선택</label>
            <select id="community-game" required value={draft.gameType}
              onChange={(event) => update({ gameType: event.target.value, requestId: crypto.randomUUID() })}>
              {Object.entries(games).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
            </select>
          </div>
          <p className="onboarding-hint">기존 커뮤니티에 참여하려면 <a href="/communities.html">초대 코드로 참여하기</a>를 이용해주세요.</p>
          <div className="creation-actions"><a className="text-button" href="/communities.html">취소</a><button>계속하기 →</button></div>
        </form>}
        {draft.step === 2 && <section>
          <div className="onboarding-heading">
            <h1 id="creation-title" ref={heading} tabIndex={-1}>Discord를 사용하고 있나요?</h1>
            <p>연결하면 멤버와 역할을 더욱 편리하게 관리할 수 있어요.</p>
          </div>
          {message && <p className="message" role="alert">{message}</p>}
          {draft.discord ? <>
            <div className="onboarding-connected"><span className="onboarding-status">연결 준비 완료</span>
              <h2>{draft.discord.guildName}</h2><p>커뮤니티를 만들 때 이 서버가 함께 연결됩니다.</p>
              <button className="text-button" onClick={() => update({ discord: null, connecting: true, requestId: crypto.randomUUID() })}>다른 Discord 서버 선택</button>
              <button className="text-button" onClick={() => update({ discord: null, step: 3, connecting: false, requestId: crypto.randomUUID() })}>나중에 연결하기</button>
            </div>
            <div className="creation-actions">
              <button className="text-button" onClick={() => update({ step: 1 })}>← 이전</button>
              <button onClick={() => update({ step: 3 })}>계속하기 →</button>
            </div>
          </> : <DiscordConnectionFlow creation onConnected={(discord) =>
            update({ discord, step: 3, connecting: false, requestId: crypto.randomUUID() })}
            onBack={() => update({ step: 1 })}
            onLater={() => update({ discord: null, step: 3, connecting: false, requestId: crypto.randomUUID() })} />
          }
        </section>}
        {draft.step === 3 && <section aria-busy={creating}>
          <div className="onboarding-heading">
            <h1 id="creation-title" ref={heading} tabIndex={-1}>거의 다 됐어요!</h1>
            <p>함께할 공간을 만들기 전에 마지막으로 확인해주세요.</p>
          </div>
          <article className="creation-preview" aria-label="생성할 커뮤니티 미리보기">
            <span className="creation-preview-mark" aria-hidden="true">{draft.name.charAt(0) || "G"}</span>
            <p className="creation-preview-caption">우리의 새로운 공간</p>
            <h2>{draft.name}</h2>
            <p>{games[draft.gameType]}</p>
            <p className="creation-preview-discord">Discord · {draft.discord?.guildName || "나중에 연결"}</p>
          </article>
          {draft.pending && !creating && <p className="onboarding-hint" role="status">생성 결과를 확인하는 중입니다. 응답을 받지 못했다면 같은 정보로 다시 시도해 주세요.</p>}
          <div className="creation-actions">
            <button className="text-button" disabled={creating || draft.pending} onClick={() => update({ step: 2 })}>← 이전</button>
            <button className="creation-submit" disabled={creating} aria-busy={creating} onClick={createCommunity}>{creating ? "만드는 중..." : "커뮤니티 만들기"}</button>
          </div>
        </section>}
      </>}
      {message && draft?.step !== 2 && <p className="message" role="alert">{message}</p>}
    </main>
    <SiteFooter />
  </div>;
}
