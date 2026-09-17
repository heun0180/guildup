import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function CommunityRoleSettingsPage() {
  const { community } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const encodedId = encodeURIComponent(communityId || "");
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [savingUserId, setSavingUserId] = useState(null);
  const [message, setMessage] = useState("");
  const [success, setSuccess] = useState("");

  useEffect(() => {
    let cancelled = false;
    api(`/api/communities/${encodedId}/users`).then((result) => { if (!cancelled) setUsers(result); })
      .catch((error) => { if (!redirectToLogin(error) && !cancelled) setMessage(error.message || "커뮤니티 권한을 불러오지 못했습니다."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [encodedId]);

  async function changeRole(userId, role) {
    setSavingUserId(userId); setMessage(""); setSuccess("");
    try {
      const updated = await api(`/api/communities/${encodedId}/users/${encodeURIComponent(userId)}/role`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ role }) });
      setUsers((current) => current.map((item) => item.userId === userId ? updated : item));
      setSuccess(`${updated.nickname}님의 역할을 ${updated.role}(으)로 변경했습니다.`);
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message || "커뮤니티 역할을 변경하지 못했습니다."); }
    finally { setSavingUserId(null); }
  }

  return <DashboardLayout active="settings" communityId={communityId} community={community}><div className="dashboard-content narrow-content">
    <div className="page-heading settings-page-heading"><div><p className="eyebrow">Community roles</p><h1>GuildUp 커뮤니티 권한</h1><p>Discord 권한과 별개로 GuildUp에서 사용할 운영 역할을 관리합니다.</p></div><a className="secondary-button" href={`/community-settings.html?communityId=${encodedId}`}>설정 목록</a></div>
    {loading && <p className="panel page-state" role="status">커뮤니티 권한을 불러오는 중입니다.</p>}{message && <p className="message" role="alert">{message}</p>}{success && <p className="success-message page-success" role="status">{success}</p>}
    {!loading && <section className="panel role-settings-panel"><div className="role-settings-copy settings-copy-first"><h2>사용자 권한</h2><p>OWNER만 다른 사용자를 ADMIN 또는 MEMBER로 변경할 수 있습니다.</p></div><div className="member-table-wrap"><table className="member-table"><thead><tr><th>사용자</th><th>현재 역할</th><th>권한 변경</th></tr></thead><tbody>{users.map((item) => <tr key={item.userId}><td><strong>{item.nickname}</strong></td><td><span className="table-badge">{item.role}</span></td><td>{community?.role === "OWNER" && item.role !== "OWNER" ? <select value={item.role} disabled={savingUserId !== null} aria-label={`${item.nickname} 역할`} onChange={(event) => changeRole(item.userId, event.target.value)}><option value="MEMBER">MEMBER</option><option value="ADMIN">ADMIN</option></select> : <span className="secondary-cell">{item.role === "OWNER" ? "최고 관리자" : "OWNER만 변경 가능"}</span>}</td></tr>)}</tbody></table></div>{users.length === 0 && <p className="empty-state">커뮤니티 사용자가 없습니다.</p>}</section>}
  </div></DashboardLayout>;
}
