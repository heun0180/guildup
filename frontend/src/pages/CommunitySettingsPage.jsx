import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { loadGameNicknameStatus } from "../gameNicknameStatus.js";
import { useCommunity } from "../community/CommunityContext.jsx";
import { canDeleteCommunity, matchesCommunityName } from "../communityDeletion.js";

const UNKNOWN_STATUS = { state: "error", title: "상태를 확인하지 못했습니다.", description: "잠시 후 다시 시도해 주세요." };

function SettingsCard({ icon, tone, title, description, status, href, buttonLabel }) {
  return (
    <article className="panel settings-menu-card">
      <div className="settings-menu-card-heading">
        <span className={`management-card-icon ${tone}`}><Icon name={icon} size={22} /></span>
        <span className={`settings-state-badge ${status.state}`}>{status.label}</span>
      </div>
      <div className="settings-menu-card-copy">
        <h2>{title}</h2>
        <p>{description}</p>
        <strong>{status.title}</strong>
        <span>{status.description}</span>
      </div>
      <a className="secondary-button" href={href}>{buttonLabel}<Icon name="arrow" size={16} /></a>
    </article>
  );
}

export default function CommunitySettingsPage() {
  const { community, clearCommunity } = useCommunity();
  const navigate = useNavigate();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const encodedId = encodeURIComponent(communityId || "");
  const nicknameGame = community?.games?.find((game) => game.capabilities?.includes("NICKNAME_SYNC"));
  const activityGame = community?.games?.find((game) => game.capabilities?.includes("ACTIVITY"));
  const nicknameApi = nicknameGame ? `/api/communities/${encodedId}/games/${nicknameGame.communityGameId}/nickname-rule` : null;
  const activityApi = activityGame ? `/api/communities/${encodedId}/games/${activityGame.communityGameId}/activity-rule` : null;
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [roleSettings, setRoleSettings] = useState(null);
  const [nicknameStatus, setNicknameStatus] = useState(null);
  const [activityRule, setActivityRule] = useState(null);
  const [communityUsers, setCommunityUsers] = useState(null);
  const [rankingPeriod, setRankingPeriod] = useState(null);
  const [savingRanking, setSavingRanking] = useState(false);
  const [rankingNotice, setRankingNotice] = useState("");
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteConfirmation, setDeleteConfirmation] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");

  useEffect(() => {
    if (!validId || !community) {
      if (!validId) setLoading(false);
      return;
    }
    let cancelled = false;
    async function load() {
      setLoading(true);
      setMessage("");
      const requests = await Promise.allSettled([
        community.discordConnected
          ? api(`/api/communities/${encodedId}/member-role-settings`)
          : Promise.resolve({ roles: [] }),
        community.discordConnected && nicknameApi
          ? loadGameNicknameStatus({
              loadStatus: () => api(`${nicknameApi}/status`),
              loadRule: () => api(nicknameApi),
            })
          : Promise.resolve({ configured: false }),
        activityApi ? api(activityApi) : Promise.resolve(null),
        api(`/api/communities/${encodedId}/users`),
        api(`/api/communities/${encodedId}/ranking-settings`),
      ]);
      if (cancelled) return;
      const unauthorized = requests.find((result) => result.status === "rejected" && result.reason?.status === 401);
      if (unauthorized && redirectToLogin(unauthorized.reason)) return;
      setRankingPeriod(requests[4].status === "fulfilled" ? requests[4].value.periodType : null);
      setRoleSettings(requests[0].status === "fulfilled" ? requests[0].value : UNKNOWN_STATUS);
      setNicknameStatus(requests[1].status === "fulfilled" ? requests[1].value : UNKNOWN_STATUS);
      setActivityRule(requests[2].status === "fulfilled" ? requests[2].value : UNKNOWN_STATUS);
      setCommunityUsers(requests[3].status === "fulfilled" ? requests[3].value : UNKNOWN_STATUS);
      if (requests.some((result) => result.status === "rejected")) {
        setMessage("일부 설정 상태를 확인하지 못했습니다. 각 설정 페이지에서 다시 확인해 주세요.");
      }
      setLoading(false);
    }
    load();
    return () => { cancelled = true; };
  }, [community, encodedId, validId, nicknameApi, activityApi]);

  useEffect(() => {
    if (!deleteModalOpen) return undefined;
    function closeOnEscape(event) {
      if (event.key === "Escape" && !deleting) {
        setDeleteModalOpen(false);
        setDeleteConfirmation("");
        setDeleteError("");
      }
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [deleteModalOpen, deleting]);

  async function saveRanking(event) {
    event.preventDefault();
    if (savingRanking || !rankingPeriod) return;
    setSavingRanking(true);
    setRankingNotice("");
    try {
      const result = await api(`/api/communities/${encodedId}/ranking-settings`, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ periodType: rankingPeriod }),
      });
      setRankingPeriod(result.periodType);
      setRankingNotice("랭킹 설정을 저장했습니다.");
    } catch (error) {
      if (!redirectToLogin(error)) setRankingNotice(error.message || "랭킹 설정을 저장하지 못했습니다.");
    } finally { setSavingRanking(false); }
  }

  function openDeleteModal() {
    setDeleteConfirmation("");
    setDeleteError("");
    setDeleteModalOpen(true);
  }

  function closeDeleteModal() {
    if (deleting) return;
    setDeleteModalOpen(false);
    setDeleteConfirmation("");
    setDeleteError("");
  }

  async function deleteCommunity(event) {
    event.preventDefault();
    if (deleting || !matchesCommunityName(deleteConfirmation, community?.name)) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await api(`/api/communities/${encodedId}`, { method: "DELETE" });
      clearCommunity();
      navigate("/communities.html", { replace: true });
    } catch (error) {
      if (!redirectToLogin(error)) {
        setDeleteError(error.message || "커뮤니티를 삭제하지 못했습니다.");
      }
      setDeleting(false);
    }
  }

  const settingsUrl = (path) => `${path}?communityId=${encodedId}`;
  const gameSettingsUrl = (path, game) => `${path}?communityId=${encodedId}&communityGameId=${encodeURIComponent(game?.communityGameId || "")}`;
  const discordStatus = !community?.discordConnected
    ? { state: "required", label: "연결 필요", title: "Discord 서버 연결이 필요합니다.", description: "서버를 연결한 뒤 클랜원 역할을 선택할 수 있습니다." }
    : roleSettings === UNKNOWN_STATUS
    ? { ...UNKNOWN_STATUS, label: "확인 필요" }
    : roleSettings?.roles?.length > 0
    ? { state: "complete", label: "설정 완료", title: `${roleSettings.roles.length}개 역할 사용 중`, description: "선택한 Discord 역할을 클랜원으로 분류합니다." }
    : { state: "required", label: "설정 필요", title: "클랜원 역할을 선택해 주세요.", description: "Discord 역할을 기준으로 클랜원을 자동 분류할 수 있습니다." };
  const gameStatus = !community?.discordConnected
    ? { state: "required", label: "연결 필요", title: "Discord 서버 연결이 필요합니다.", description: "서버를 연결한 뒤 닉네임 규칙을 분석할 수 있습니다." }
    : nicknameStatus === UNKNOWN_STATUS
    ? { ...UNKNOWN_STATUS, label: "확인 필요" }
    : nicknameStatus?.configured
    ? { state: "complete", label: "설정 완료", title: "닉네임 규칙 적용 중", description: "Discord 닉네임에서 인게임 닉네임을 자동으로 구분합니다." }
    : { state: "required", label: "설정 필요", title: "인게임 닉네임 규칙이 없습니다.", description: "클랜원 활동을 조회하기 전에 규칙을 설정해 주세요." };
  const activityStatus = activityRule === UNKNOWN_STATUS
    ? { ...UNKNOWN_STATUS, label: "확인 필요" }
    : activityRule
    ? { state: "complete", label: "사용 중", title: `최근 ${activityRule.activityPeriodDays}일 기준`, description: `같은 팀에 클랜원 ${activityRule.minimumClanMembersInRoster}명 이상이면 활동으로 인정합니다.` }
    : { state: "required", label: "설정 필요", title: "활동 규칙을 확인해 주세요.", description: "활동 기간과 인정 인원을 설정할 수 있습니다." };
  const permissionStatus = communityUsers === UNKNOWN_STATUS
    ? { ...UNKNOWN_STATUS, label: "확인 필요" }
    : Array.isArray(communityUsers)
    ? { state: "complete", label: "사용 중", title: `${communityUsers.length}명 참여 중`, description: `운영진 ${communityUsers.filter((user) => user.role !== "MEMBER").length}명이 커뮤니티를 관리하고 있습니다.` }
    : { state: "required", label: "확인 필요", title: "커뮤니티 권한을 확인해 주세요.", description: "GuildUp 운영 역할을 관리할 수 있습니다." };

  return (
    <DashboardLayout active="settings" communityId={communityId} community={community}>
      <div className="dashboard-content settings-home-content">
        <div className="page-heading">
          <p className="eyebrow">Settings</p>
          <h1>커뮤니티 설정</h1>
          <p>변경할 설정을 선택하세요. 각 설정에서 변경 내용을 저장할 수 있습니다.</p>
        </div>
        {loading && <p className="panel page-state" role="status">설정 상태를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {!loading && community && <>
          <section className="settings-menu-grid" aria-label="커뮤니티 설정 목록">
            <SettingsCard icon="discord" tone="discord" title="Discord 클랜원 역할 설정" description="어떤 Discord 역할을 GuildUp 클랜원으로 인식할지 선택합니다." status={discordStatus} href={community.discordConnected ? settingsUrl("/discord-member-role-settings.html") : settingsUrl("/discord-connect.html")} buttonLabel={community.discordConnected ? "역할 설정으로 이동" : "Discord 연결하기"} />
            {nicknameGame && <SettingsCard icon="game" tone="activity" title={`${nicknameGame.gameName} 인게임 닉네임 설정`} description="Discord 닉네임에서 게임 닉네임을 추출하는 규칙을 관리합니다." status={gameStatus} href={gameSettingsUrl("/game-nickname-settings.html", nicknameGame)} buttonLabel="닉네임 설정으로 이동" />}
            {activityGame && <SettingsCard icon="activity" tone="activity" title={`${activityGame.gameName} 클랜 활동 규칙`} description="게임 활동을 인정할 조회 기간과 최소 클랜원 수를 정합니다." status={activityStatus} href={gameSettingsUrl("/activity-rule-settings.html", activityGame)} buttonLabel="활동 규칙으로 이동" />}
            <SettingsCard icon="users" tone="members" title="GuildUp 커뮤니티 권한" description="커뮤니티 사용자와 GuildUp 관리자 역할을 관리합니다." status={permissionStatus} href={settingsUrl("/community-role-settings.html")} buttonLabel="권한 설정으로 이동" />
          </section>
          <form className="panel ranking-settings" onSubmit={saveRanking}>
            <h2>랭킹 설정</h2>
            <fieldset disabled={savingRanking || rankingPeriod == null || !["OWNER", "ADMIN"].includes(community.role)}>
              <legend>집계 주기</legend>
              {[["MONTHLY", "월간"], ["QUARTERLY", "분기"], ["ALL_TIME", "전체 누적"]].map(([value, label]) =>
                <label key={value}><input type="radio" name="rankingPeriod" value={value} checked={rankingPeriod === value} onChange={() => setRankingPeriod(value)} />{label}</label>)}
            </fieldset>
            <p>선택한 기간의 출석과 킬내기 점수를 합산합니다. 기존 점수는 보존됩니다.</p>
            {["OWNER", "ADMIN"].includes(community.role) && <button type="submit" disabled={savingRanking || rankingPeriod == null}>{savingRanking ? "저장 중..." : "랭킹 설정 저장"}</button>}
            {rankingNotice && <p role="status">{rankingNotice}</p>}
          </form>
          {canDeleteCommunity(community.role) && <section className="community-danger-zone" aria-labelledby="danger-zone-title">
            <div>
              <p className="eyebrow danger-zone-label">위험 구역</p>
              <h2 id="danger-zone-title">커뮤니티 삭제</h2>
              <p>커뮤니티와 관련된 모든 데이터를 삭제합니다.</p>
              <strong>삭제한 커뮤니티는 복구할 수 없습니다.</strong>
            </div>
            <button type="button" className="permanent-delete-button" onClick={openDeleteModal}>커뮤니티 삭제</button>
          </section>}
        </>}
      </div>
      {deleteModalOpen && <div className="modal-backdrop community-delete-backdrop" role="presentation" onMouseDown={(event) => {
        if (event.target === event.currentTarget) closeDeleteModal();
      }}>
        <form className="community-delete-modal" role="dialog" aria-modal="true" aria-labelledby="community-delete-title" onSubmit={deleteCommunity}>
          <h2 id="community-delete-title">커뮤니티 삭제</h2>
          <p>정말 이 커뮤니티를 삭제하시겠습니까?</p>
          <p>커뮤니티를 삭제하면 아래 데이터가 함께 삭제됩니다.</p>
          <ul>
            <li>커뮤니티 멤버 정보</li>
            <li>커뮤니티 게임 연결 정보</li>
            <li>Discord 연결 정보</li>
            <li>PUBG 관련 커뮤니티 데이터</li>
            <li>빙고 이벤트 및 진행 데이터</li>
            <li>킬내기 데이터</li>
            <li>커뮤니티에 속한 기타 설정 및 콘텐츠</li>
          </ul>
          <p className="community-delete-warning">삭제된 데이터는 복구할 수 없습니다.</p>
          <label className="community-delete-confirmation">
            <span>계속하려면 아래에 커뮤니티 이름을 입력해주세요.</span>
            <strong>{community.name}</strong>
            <input autoFocus value={deleteConfirmation} disabled={deleting}
              onChange={(event) => setDeleteConfirmation(event.target.value)}
              placeholder="커뮤니티 이름 입력" autoComplete="off" />
          </label>
          {deleteError && <p className="message" role="alert">{deleteError}</p>}
          <div className="community-delete-actions">
            <button type="button" className="secondary-button" disabled={deleting} onClick={closeDeleteModal}>취소</button>
            <button type="submit" className="permanent-delete-button"
              disabled={deleting || !matchesCommunityName(deleteConfirmation, community.name)}>
              {deleting ? "삭제 중..." : "영구 삭제"}
            </button>
          </div>
        </form>
      </div>}
    </DashboardLayout>
  );
}
