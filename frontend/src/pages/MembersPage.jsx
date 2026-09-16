import { useCallback, useEffect, useRef, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { canManageCommunity } from "../communityAccess.js";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function MembersPage() {
  const { community } = useCommunity();
  const params = new URLSearchParams(window.location.search);
  const communityId = params.get("communityId");
  const guildId = params.get("guildId");
  const discordRoles = params.get("discordRoles") === "true";
  const communityValid = /^\d+$/.test(communityId ?? "");
  const guildValid = /^\d+$/.test(guildId ?? "");
  const roleMode = (discordRoles && communityValid) || (!discordRoles && guildValid);
  const rolesUrl = discordRoles
    ? `/api/communities/${encodeURIComponent(communityId)}/discord/roles`
    : `/api/discord/guilds/${encodeURIComponent(guildId)}/roles`;
  const [roles, setRoles] = useState([]);
  const [selectedRole, setSelectedRole] = useState(null);
  const [members, setMembers] = useState([]);
  const [nickname, setNickname] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState("");
  const [memberRolesConfigured, setMemberRolesConfigured] = useState(null);
  const roleRequestId = useRef(0);

  const handleError = useCallback((error, fallback) => {
    if (!redirectToLogin(error)) setMessage(error.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다." : fallback);
  }, []);

  const loadCommunityMembers = useCallback(async () => {
    setLoading(true);
    setMessage("");
    try {
      setMembers(await api(`/api/communities/${encodeURIComponent(communityId)}/members`));
    } catch (error) {
      handleError(error, "클랜원 목록 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setLoading(false);
    }
  }, [communityId, handleError]);

  const selectRole = useCallback(async (role) => {
    const requestId = ++roleRequestId.current;
    setSelectedRole(role);
    setLoading(true);
    setMessage("");
    try {
      const roleMembers = await api(`${rolesUrl}/${encodeURIComponent(role.id)}/members`);
      if (requestId === roleRequestId.current) setMembers(roleMembers);
    } catch (error) {
      if (requestId === roleRequestId.current) {
        setMembers([]);
        handleError(error, "선택한 Discord 역할의 멤버 목록 요청에 실패했습니다.");
      }
    } finally {
      if (requestId === roleRequestId.current) setLoading(false);
    }
  }, [handleError, rolesUrl]);

  useEffect(() => {
    let cancelled = false;
    if (roleMode) {
      roleRequestId.current += 1;
      setSelectedRole(null);
      setMembers([]);
      setLoading(true);
      api(rolesUrl).then((data) => {
        if (cancelled) return;
        setRoles(data);
        setLoading(false);
      }).catch((error) => {
        if (cancelled) return;
        handleError(error, "Discord 서버 연결과 Guild ID를 확인해 주세요.");
        setLoading(false);
      });
    } else if (communityValid) {
      loadCommunityMembers();
    } else {
      setMessage("주소에 올바른 guildId 또는 communityId를 입력해 주세요.");
      setLoading(false);
    }
    return () => { cancelled = true; };
  }, [communityValid, handleError, loadCommunityMembers, roleMode, rolesUrl]);

  useEffect(() => {
    if (!communityValid) return;
    api(`/api/communities/${encodeURIComponent(communityId)}/member-role-settings`)
      .then((settings) => setMemberRolesConfigured(settings.roles.length > 0))
      .catch((error) => {
        if (!redirectToLogin(error) && error.status !== 404) setMemberRolesConfigured(null);
      });
  }, [communityId, communityValid]);

  async function addMember(event) {
    event.preventDefault();
    const normalized = nickname.trim();
    if (!normalized) return setMessage("클랜원 이름을 입력해 주세요.");
    setSaving(true);
    setMessage("");
    try {
      await api(`/api/communities/${encodeURIComponent(communityId)}/members`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname: normalized }),
      });
      setNickname("");
      await loadCommunityMembers();
    } catch (error) {
      handleError(error, "클랜원 추가에 실패했습니다. Community ID를 확인해 주세요.");
    } finally {
      setSaving(false);
    }
  }

  const title = roleMode ? selectedRole?.name || "Discord 역할별 멤버" : "클랜원 목록";
  const count = loading ? "불러오는 중..." : roleMode
    ? selectedRole ? `${selectedRole.name} (${members.length}명)` : "역할을 선택해 주세요."
    : `클랜원 ${members.length}명`;
  const normalizedSearch = search.trim().toLocaleLowerCase();
  const filteredMembers = members.filter((member) => {
    if (!normalizedSearch) return true;
    return [member.displayName, member.nickname, member.username, member.discordDisplayName, member.discordUsername]
      .filter(Boolean).some((value) => value.toLocaleLowerCase().includes(normalizedSearch));
  });
  const canManage = canManageCommunity(community?.role);
  const discordConnected = community?.discordConnected === true;
  const syncSetupRequired = Boolean(community)
    && (!discordConnected || memberRolesConfigured === false);
  const syncSetupUrl = discordConnected
    ? `/community-settings.html?communityId=${encodeURIComponent(communityId)}#role-settings-title`
    : `/discord-connect.html?communityId=${encodeURIComponent(communityId)}`;
  const lastSyncedAt = community?.lastMemberSyncedAt
    ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" })
      .format(new Date(community.lastMemberSyncedAt))
    : "아직 전체 확인 전";
  const formatJoinedAt = (value) => value
    ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium" }).format(new Date(value)) : "-";

  return (
    <DashboardLayout active={roleMode ? "roles" : "members"} communityId={communityId} onError={setMessage}>
      <div className="dashboard-content">
        <div className="page-heading is-compact">
          <div className="members-heading-row">
            <div>
              <p className="eyebrow">{roleMode ? "Discord" : "Members"}</p>
              <h1 id="member-list-title">{roleMode ? "Discord 역할" : "클랜원"}</h1>
            </div>
            {!roleMode && canManage && <div className="members-heading-actions">
              <a className="secondary-button" href={`/member-activities.html?communityId=${encodeURIComponent(communityId)}`}>
                <Icon name="activity" size={17} />활동 상태 보기
              </a>
              {syncSetupRequired && (
                <a className="secondary-button" href={syncSetupUrl}>
                  <Icon name="discord" size={17} />
                  {discordConnected ? "클랜원 역할 설정 필요" : "Discord 연결 필요"}
                </a>
              )}
            </div>}
          </div>
          {!roleMode && canManage && <p className="last-synced-at">
            {memberRolesConfigured
              ? `Discord 역할 변경사항은 자동 반영됩니다. 마지막 전체 확인: ${lastSyncedAt}`
              : `마지막 전체 확인: ${lastSyncedAt}`}
          </p>}
        </div>
        {canManage && syncSetupRequired && (
          <aside className="member-role-guide" aria-label="클랜원 역할 설정 안내">
            <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
            <div>
              <h2>{discordConnected ? "아직 클랜원 역할이 설정되지 않았습니다." : "Discord 서버가 연결되지 않았습니다."}</h2>
              <p>{discordConnected
                ? "동기화할 Discord 역할을 먼저 선택해 주세요."
                : "이 커뮤니티에 Discord 서버를 연결해야 클랜원을 동기화할 수 있습니다."}</p>
            </div>
            <a className="secondary-button" href={syncSetupUrl}>
              {discordConnected ? "클랜원 역할 설정" : "Discord 서버 연결"} <Icon name="arrow" size={17} />
            </a>
          </aside>
        )}
        <section className="panel members-panel" aria-labelledby="member-list-title">
          <div className="members-toolbar">
            <div><h2>{title}</h2><p className="member-count">{count}</p></div>
            <label className="search-field"><span className="sr-only">멤버 검색</span><Icon name="search" size={18} />
              <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="멤버 검색" autoComplete="off" />
            </label>
          </div>
          {roleMode && roles.length > 0 && <div className="role-tabs" role="tablist" aria-label="Discord 역할">
            {roles.map((role) => <button className="role-tab" type="button" role="tab" key={role.id}
              aria-selected={selectedRole?.id === role.id} onClick={() => selectRole(role)}>
              <span className="role-symbol" aria-hidden="true">#</span>{role.name}
              {selectedRole?.id === role.id && !loading && <span className="role-count">{members.length}</span>}
            </button>)}
          </div>}
          {!roleMode && communityValid && canManage && <form className="add-member-form" onSubmit={addMember}>
            <div><label htmlFor="nickname">새 클랜원 추가</label></div>
            <div className="add-member-controls">
              <input id="nickname" placeholder="클랜원 이름 입력" autoComplete="off" required value={nickname} onChange={(event) => setNickname(event.target.value)} />
              <button type="submit" disabled={saving}><Icon name="plus" size={17} />{saving ? "추가 중..." : "추가"}</button>
            </div>
          </form>}
          {message && <p className="message padded-message" role="alert">{message}</p>}
          {!loading && !message && members.length === 0 && <p className="empty-state">{roleMode
            ? roles.length
              ? selectedRole ? "이 역할을 가진 사용자가 없습니다." : "역할을 선택하면 해당 멤버를 불러옵니다."
              : "@everyone을 제외한 역할이 없습니다."
            : memberRolesConfigured ? "현재 ACTIVE 상태인 클랜원이 없습니다." : "등록된 클랜원이 없습니다."}</p>}
          {!loading && members.length > 0 && <div className="member-table-wrap">
            <table className="member-table">
              <thead><tr><th>멤버</th><th>Discord 계정</th><th>{roleMode ? "역할" : "Discord 가입일"}</th>{!roleMode && <th>상태</th>}</tr></thead>
              <tbody>{filteredMembers.map((member) => {
                const name = roleMode ? member.displayName : member.discordDisplayName || member.nickname;
                return <tr key={member.id ?? member.discordUserId ?? `${name}-${member.username ?? ""}`}>
                  <td><span className="member-identity"><Avatar src={roleMode ? member.avatarUrl : undefined} name={name} /><strong>{name}</strong></span></td>
                  <td className="secondary-cell">{roleMode ? `@${member.username}` : member.discordUsername ? `@${member.discordUsername}` : "수동 등록"}</td>
                  <td>{roleMode ? <span className="table-badge">{selectedRole?.name}</span> : <span className="secondary-cell">{formatJoinedAt(member.discordJoinedAt)}</span>}</td>
                  {!roleMode && <td><span className="table-badge">{member.status}</span></td>}
                </tr>;
              })}</tbody>
            </table>
            {filteredMembers.length === 0 && <p className="empty-state">검색 결과가 없습니다.</p>}
          </div>}
        </section>
      </div>
    </DashboardLayout>
  );
}
