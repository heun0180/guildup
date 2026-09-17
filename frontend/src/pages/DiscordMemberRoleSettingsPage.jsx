import { useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function DiscordMemberRoleSettingsPage() {
  const { community } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const encodedId = encodeURIComponent(communityId || "");
  const [roles, setRoles] = useState([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [success, setSuccess] = useState("");
  const selected = useMemo(() => new Set(selectedRoleIds), [selectedRoleIds]);

  useEffect(() => {
    if (!community) return;
    if (!community.discordConnected) { setLoading(false); return; }
    let cancelled = false;
    Promise.all([
      api(`/api/communities/${encodedId}/discord/roles`),
      api(`/api/communities/${encodedId}/member-role-settings`),
    ]).then(([availableRoles, settings]) => {
      if (cancelled) return;
      setRoles(availableRoles);
      setSelectedRoleIds(settings.roles.map((role) => role.discordRoleId));
    }).catch((error) => {
      if (!redirectToLogin(error) && !cancelled) setMessage(error.message || "Discord 역할 설정을 불러오지 못했습니다.");
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [community, encodedId]);

  function toggleRole(roleId) {
    setSuccess("");
    setSelectedRoleIds((current) => current.includes(roleId) ? current.filter((id) => id !== roleId) : [...current, roleId]);
  }

  async function save() {
    setSaving(true); setMessage(""); setSuccess("");
    let settingsSaved = false;
    try {
      const result = await api(`/api/communities/${encodedId}/member-role-settings`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ discordRoleIds: selectedRoleIds }) });
      settingsSaved = true;
      setSelectedRoleIds(result.roles.map((role) => role.discordRoleId));
      const sync = await api(`/api/communities/${encodedId}/members/sync`, { method: "POST" });
      setSuccess(`설정 저장과 Discord 전체 확인을 완료했습니다. 신규 ${sync.createdMembers}명 / 업데이트 ${sync.updatedMembers + sync.reactivatedMembers}명 / 탈퇴 처리 ${sync.leftMembers}명`);
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(settingsSaved ? "역할 설정은 저장했지만 Discord 클랜원 동기화에 실패했습니다." : error.message || "클랜원 역할 설정을 저장하지 못했습니다.");
    } finally { setSaving(false); }
  }

  return (
    <DashboardLayout active="settings" communityId={communityId} community={community}>
      <div className="dashboard-content narrow-content">
        <div className="page-heading settings-page-heading"><div><p className="eyebrow">Discord roles</p><h1>Discord 클랜원 역할 설정</h1><p>GuildUp 클랜원으로 인식할 Discord 역할을 선택합니다.</p></div><a className="secondary-button" href={`/community-settings.html?communityId=${encodedId}`}>설정 목록</a></div>
        {loading && <p className="panel page-state" role="status">Discord 역할을 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {success && <p className="success-message page-success" role="status">{success}</p>}
        {!loading && community && !community.discordConnected && <section className="panel disconnected-nickname-panel"><span className="management-card-icon discord"><Icon name="discord" size={22} /></span><div><h2>Discord 서버 연결이 필요합니다.</h2><p>서버를 연결한 뒤 클랜원 역할을 설정할 수 있습니다.</p></div><a className="button-link" href={`/discord-connect.html?communityId=${encodedId}`}>Discord 연결하기 <Icon name="arrow" size={17} /></a></section>}
        {!loading && community?.discordConnected && <section className="panel role-settings-panel">
          <div className="connected-guild-row"><span className="management-card-icon discord"><Icon name="discord" size={22} /></span><div><p>연결된 서버</p><strong>{community.discordGuildName || community.discordGuildId}</strong></div><span className="status-badge connected"><span aria-hidden="true" />연결됨</span></div>
          <div className="role-settings-copy"><h2>클랜원 역할 선택</h2><p>선택한 역할을 가진 Discord 사용자를 GuildUp 클랜원으로 동기화합니다.</p></div>
          {roles.length > 0 ? <div className="role-check-list">{roles.map((role) => <label className={`role-check-item${selected.has(role.id) ? " is-selected" : ""}`} key={role.id}><input type="checkbox" checked={selected.has(role.id)} disabled={saving} onChange={() => toggleRole(role.id)} /><span className="custom-checkbox"><Icon name="check" size={15} /></span><span className="role-check-name">{role.name}</span><span className="role-check-id">{role.id}</span></label>)}</div> : <p className="role-settings-empty">선택할 수 있는 Discord 역할이 없습니다.</p>}
          <div className="settings-actions"><span>{selectedRoleIds.length}개 역할 선택됨</span><button type="button" disabled={saving} onClick={save}>{saving ? "저장 중..." : "설정 저장"}</button></div>
        </section>}
      </div>
    </DashboardLayout>
  );
}
