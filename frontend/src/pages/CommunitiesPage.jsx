import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import Icon from "../components/Icon.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import DiscordAccountNotice from "../components/DiscordAccountNotice.jsx";

export default function CommunitiesPage() {
  const navigate = useNavigate();
  const [communities, setCommunities] = useState([]);
  const [discoverable, setDiscoverable] = useState([]);
  const [discordConnected, setDiscordConnected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteCode, setInviteCode] = useState("");
  const [joiningInvite, setJoiningInvite] = useState(false);
  const [message, setMessage] = useState("");
  const [joiningId, setJoiningId] = useState(null);

  const loadCommunities = useCallback(async () => {
    try {
      const [memberships, candidates, account] = await Promise.allSettled([
        api("/api/auth/me/communities"),
        api("/api/community-discoveries/discord"),
        api("/api/auth/account"),
      ]);
      if (memberships.status === "rejected") throw memberships.reason;
      setCommunities(memberships.value);
      if (account.status === "fulfilled") setDiscordConnected(account.value.discordConnected);
      setDiscoverable(candidates.status === "fulfilled" ? candidates.value : []);
      if (candidates.status === "rejected" && !redirectToLogin(candidates.reason)) {
        setMessage("내 커뮤니티는 확인했지만 Discord 가입 가능 목록을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.");
      }
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadCommunities(); }, [loadCommunities]);

  async function joinByInvitation(event) {
    event.preventDefault();
    if (joiningInvite) return;
    setJoiningInvite(true);
    setMessage("");
    try {
      const joined = await api("/api/communities/join-by-invitation", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ inviteCode: inviteCode.trim() }),
      });
      navigate(`/community-dashboard.html?communityId=${encodeURIComponent(joined.communityId)}`);
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
    } finally { setJoiningInvite(false); }
  }

  async function joinCommunity(communityId) {
    setJoiningId(communityId);
    setMessage("");
    try {
      const joined = await api(`/api/community-discoveries/discord/${encodeURIComponent(communityId)}/join`, {
        method: "POST",
      });
      navigate(`/community-dashboard.html?communityId=${encodeURIComponent(joined.communityId)}`);
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
      setJoiningId(null);
    }
  }

  return (
    <div className="public-page onboarding-page">
      <AppHeader actions onError={setMessage} />
      <main className="public-main communities-main" aria-labelledby="communities-title">
        <div className="onboarding-heading">
          <h1 id="communities-title">어디로 들어갈까요?</h1>
          <p>참여 중인 커뮤니티를 선택하거나 새로운 커뮤니티를 시작해보세요.</p>
        </div>
        {loading && <p className="onboarding-empty" role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {!loading && communities.length === 0 && (
          <div className="onboarding-empty"><h2>첫 커뮤니티에서 함께 시작해보세요.</h2>
            <p>초대 코드로 참여하거나 직접 운영하는 커뮤니티를 만들 수 있어요.</p></div>
        )}
        <section aria-label="내 커뮤니티" className="community-grid">
          {communities.map((community) => (
            <a className="community-card" key={community.id}
              href={`/community-dashboard.html?communityId=${encodeURIComponent(community.id)}`}
              aria-label={`${community.name} 커뮤니티 입장`}>
              <span className="community-card-mark" aria-hidden="true">{community.name?.charAt(0) || "G"}</span>
              <div className="community-card-copy">
                <h2>{community.name}</h2>
                <p>{community.gameName || "게임 미설정"} <span aria-hidden="true">·</span> {community.memberCount ?? 0}명</p>
              </div>
              <span className="community-card-link">입장 <Icon name="arrow" size={17} /></span>
            </a>
          ))}
          {!loading && <a className="community-create-link" href="/community-create.html">+ 새 커뮤니티 만들기</a>}
        </section>
        <aside className="community-secondary-actions" aria-label="초대 코드로 참여">
          <p>함께할 커뮤니티의 초대 코드가 있나요?</p>
          <button className="text-button" type="button" aria-expanded={inviteOpen} aria-controls="invitation-form"
                  onClick={() => setInviteOpen(!inviteOpen)}>초대 코드로 참여하기</button>
          {inviteOpen && <form id="invitation-form" className="invitation-form" onSubmit={joinByInvitation} aria-busy={joiningInvite}>
            <label htmlFor="invite-code">초대 코드</label>
            <div className="invitation-controls">
              <input id="invite-code" required value={inviteCode} maxLength={36} placeholder="운영자에게 받은 초대 코드"
                     onChange={(event) => setInviteCode(event.target.value)} />
              <button disabled={joiningInvite}>{joiningInvite ? "참여 중..." : "참여하기"}</button>
            </div>
          </form>}
        </aside>
        {!loading && discoverable.length > 0 && <section className="community-discoveries" aria-labelledby="discoverable-title">
          <div className="community-discoveries-heading">
            <h2 id="discoverable-title">가입 가능한 커뮤니티</h2>
            <p>연결된 Discord 서버에서 찾았어요.</p>
          </div>
          <div className="community-grid">
            {discoverable.map((community) => <article className="community-card is-discoverable" key={community.communityId}>
              <span className="community-card-mark" aria-hidden="true">{community.communityName?.charAt(0) || "G"}</span>
              <div className="community-card-copy">
                <h2>{community.communityName}</h2>
                <p>{community.discordGuildName || "연결된 Discord 서버"}</p>
              </div>
              <button className="text-button" type="button" disabled={joiningId !== null}
                      onClick={() => joinCommunity(community.communityId)}>
                {joiningId === community.communityId ? "가입 중..." : "GuildUp 커뮤니티 가입"}
              </button>
            </article>)}
          </div>
        </section>}
        {!loading && discordConnected === false && <DiscordAccountNotice />}
      </main>
      <SiteFooter />
    </div>
  );
}
