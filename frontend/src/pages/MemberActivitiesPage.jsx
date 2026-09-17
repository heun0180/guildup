import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import {
  activityStatus,
  activitySyncView,
  formatActivityDateTime,
  formatRelativeDays,
  sortActivityMembers,
} from "../activityView.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { canManageCommunity } from "../communityAccess.js";
import { loadActivityPageData } from "../activityPageLoader.js";
import { loadGameNicknameStatus } from "../gameNicknameStatus.js";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function MemberActivitiesPage() {
  const navigate = useNavigate();
  const { community } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [activities, setActivities] = useState(null);
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState({ key: null, direction: "asc" });
  const [configurationStatus, setConfigurationStatus] = useState(validId ? "loading" : "error");
  const [syncing, setSyncing] = useState(false);
  const [syncNow, setSyncNow] = useState(() => Date.now());
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;
    async function load() {
      setConfigurationStatus("loading");
      setMessage("");
      try {
        const result = await loadActivityPageData({
          loadCommunity: () => Promise.resolve(community),
          loadNicknameStatus: () => loadGameNicknameStatus({
            loadStatus: () => api(`/api/communities/${encodeURIComponent(communityId)}/game-nickname-rule/status`),
            loadRule: () => api(`/api/communities/${encodeURIComponent(communityId)}/game-nickname-rule`),
          }),
          loadActivities: () => api(`/api/communities/${encodeURIComponent(communityId)}/member-activities`),
        });
        if (cancelled) return;
        setActivities(result.activities);
        setConfigurationStatus(result.status);
      } catch (error) {
        if (!redirectToLogin(error) && !cancelled) {
          setConfigurationStatus("error");
          setMessage(error.status === 403
            ? "클랜원 활동을 확인할 권한이 없습니다."
            : error.activityLoadStage === "activities"
            ? error.message || "저장된 클랜원 활동을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요."
            : "인게임 닉네임 설정 상태를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.");
        }
      }
    }
    load();
    return () => { cancelled = true; };
  }, [community, communityId, validId]);

  useEffect(() => {
    setSyncNow(Date.now());
    const nextAvailableAt = Date.parse(activities?.sync?.nextSyncAvailableAt);
    if (!Number.isFinite(nextAvailableAt)) return undefined;
    const delay = nextAvailableAt - Date.now();
    if (delay <= 0) return undefined;
    const timer = window.setTimeout(() => setSyncNow(Date.now()), delay + 50);
    return () => window.clearTimeout(timer);
  }, [activities?.sync?.nextSyncAvailableAt]);

  const filteredMembers = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase();
    const members = keyword ? (activities?.members || []).filter((member) =>
      [member.discordNickname, member.gameNickname]
        .filter(Boolean)
        .some((value) => value.toLocaleLowerCase().includes(keyword))
    ) : activities?.members || [];
    return sortActivityMembers(members, sort.key, sort.direction);
  }, [activities, search, sort]);

  function toggleSort(key) {
    setSort((current) => ({
      key,
      direction: current.key === key && current.direction === "asc" ? "desc" : "asc",
    }));
  }

  function sortableHeader(key, label) {
    const active = sort.key === key;
    const ariaSort = active ? (sort.direction === "asc" ? "ascending" : "descending") : "none";
    return <th aria-sort={ariaSort}>
      <button className="table-sort-button" type="button" onClick={() => toggleSort(key)}>
        <span>{label}</span>
        <span className={`table-sort-indicator${active ? " active" : ""}`} aria-hidden="true">
          {active ? (sort.direction === "asc" ? "↑" : "↓") : "↕"}
        </span>
      </button>
    </th>;
  }

  function openDetail(memberId) {
    navigate(`/member-activity.html?communityId=${encodeURIComponent(communityId)}&memberId=${encodeURIComponent(memberId)}`);
  }

  async function syncActivities() {
    if (configurationStatus !== "configured") return;
    setSyncing(true);
    setMessage("");
    try {
      const result = await api(
        `/api/communities/${encodeURIComponent(communityId)}/member-activities/sync`,
        { method: "POST" },
      );
      setActivities(result);
    } catch (error) {
      if (!redirectToLogin(error)) {
        if (error.status === 409 || error.status === 429) {
          const latest = await api(`/api/communities/${encodeURIComponent(communityId)}/member-activities`)
            .catch(() => null);
          if (latest) setActivities(latest);
        }
        setMessage(error.status === 409
          ? "이미 활동 정보를 조회 중입니다."
          : error.status === 429
          ? "아직 활동 정보를 다시 조회할 수 없습니다."
          : error.message);
      }
    } finally {
      setSyncing(false);
    }
  }

  const syncView = activitySyncView(activities?.sync, syncing, syncNow);
  const canManage = canManageCommunity(community?.role);

  return (
    <DashboardLayout active="activity" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content activity-content">
        <div className="page-heading is-compact">
          <p className="eyebrow">Activity</p>
          <h1>클랜원 활동</h1>
        </div>

        {configurationStatus === "loading" && <section className="panel activity-loading" role="status">
          <span className="activity-loader" aria-hidden="true" />
          <div><h2>인게임 닉네임 설정을 확인하고 있습니다...</h2><p>설정이 완료된 경우 저장된 활동을 이어서 불러옵니다.</p></div>
        </section>}
        {message && <p className="message" role="alert">{message}</p>}

        {configurationStatus === "notConfigured" && <section className="panel member-role-guide nickname-required-guide">
          <span className="management-card-icon activity"><Icon name="game" size={22} /></span>
          <div>
            <h2>인게임 닉네임이 설정되지 않았습니다.</h2>
            <p>활동 정보를 조회하려면 클랜원의 인게임 닉네임을 먼저 설정해야 합니다.</p>
          </div>
          <a className="secondary-button" href={`/game-nickname-settings.html?communityId=${encodeURIComponent(communityId)}`}>
            인게임 닉네임 설정
          </a>
        </section>}

        {configurationStatus === "configured" && activities && <>
          <section className="panel activity-sync-panel" aria-label="PUBG 활동 동기화 상태">
            <div className="activity-sync-copy">
              <div className="activity-sync-status">
                <p className={`activity-sync-state ${activities.sync.status.toLowerCase()}`}>{syncView.title}</p>
                {syncView.description && <p>{syncView.description}</p>}
              </div>
              <div className="activity-sync-meta">
                {activities.sync.lastSuccessfulSyncAt && <div>
                  <span>마지막 활동 조회</span>
                  <strong>{formatActivityDateTime(activities.sync.lastSuccessfulSyncAt)}</strong>
                </div>}
                {activities.sync.nextSyncAvailableAt && <div>
                  <span>다음 조회 가능</span>
                  <strong>{formatActivityDateTime(activities.sync.nextSyncAvailableAt)}</strong>
                </div>}
              </div>
            </div>
            {canManage && <button className="primary-button" type="button"
                                  disabled={syncView.buttonDisabled} onClick={syncActivities}>
              {syncView.buttonLabel}
            </button>}
          </section>

          <section className="activity-summary-grid" aria-label="클랜원 활동 요약">
            <div className="panel activity-summary-card"><span>전체</span><strong>{activities.totalMembers}</strong></div>
            <div className="panel activity-summary-card active"><span>정상</span><strong>{activities.activeMembers}</strong></div>
            <div className="panel activity-summary-card warning"><span>활동 없음</span><strong>{activities.noClanActivityMembers}</strong></div>
            <div className="panel activity-summary-card muted"><span>최근 게임 없음</span><strong>{activities.noRecentMatchMembers}</strong></div>
            <div className="panel activity-summary-card danger"><span>계정 확인 필요</span><strong>{activities.accountVerificationRequiredMembers}</strong></div>
          </section>

          <aside className="activity-rule-strip">
            <Icon name="activity" size={19} />
            <span>최근 <b>{activities.rule.activityPeriodDays}일</b> 동안 같은 팀에 클랜원이 <b>{activities.rule.minimumClanMembersInRoster}명 이상</b>인 게임을 활동으로 인정합니다.</span>
          </aside>

          <section className="panel members-panel" aria-labelledby="activity-list-title">
            <div className="members-toolbar">
              <div><h2 id="activity-list-title">활동 상태</h2><p className="member-count">클랜원 {activities.totalMembers}명</p></div>
              <label className="search-field"><span className="sr-only">클랜원 검색</span><Icon name="search" size={18} />
                <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="클랜원 검색" autoComplete="off" />
              </label>
            </div>
            {activities.members.length === 0 ? <p className="empty-state">등록된 클랜원이 없습니다.</p> :
              <div className="member-table-wrap">
                <table className="member-table activity-table">
                  <thead><tr>
                    {sortableHeader("discordNickname", "Discord 닉네임")}
                    {sortableHeader("gameNickname", "인게임 닉네임")}
                    {sortableHeader("lastClanActivityAt", "최근 클랜 활동")}
                    {sortableHeader("status", "상태")}
                  </tr></thead>
                  <tbody>{filteredMembers.map((member) => {
                    const status = activityStatus(member.status);
                    return <tr className="clickable-member-row" key={member.memberId} tabIndex="0" role="link"
                               onClick={() => openDetail(member.memberId)}
                               onKeyDown={(event) => {
                                 if (event.key === "Enter" || event.key === " ") openDetail(member.memberId);
                               }}>
                      <td><span className="member-identity"><Avatar name={member.discordNickname} /><strong>{member.discordNickname}</strong></span></td>
                      <td className={member.gameNickname ? "" : "secondary-cell"}>{member.gameNickname || "-"}</td>
                      <td>{formatRelativeDays(member.lastClanActivityAt)}</td>
                      <td><span className={`activity-status ${status.tone}`}><b>{status.symbol}</b>{status.label}</span></td>
                    </tr>;
                  })}</tbody>
                </table>
                {filteredMembers.length === 0 && <p className="empty-state">검색 결과가 없습니다.</p>}
              </div>}
          </section>
        </>}
      </div>
    </DashboardLayout>
  );
}
