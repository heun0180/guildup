import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import Avatar from "../components/Avatar.jsx";
import CommunityBackLink from "../components/CommunityBackLink.jsx";

export default function MembersPage() {
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
  const [message, setMessage] = useState("");

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
    setSelectedRole(role);
    setLoading(true);
    setMessage("");
    try {
      setMembers(await api(`${rolesUrl}/${encodeURIComponent(role.id)}/members`));
    } catch (error) {
      handleError(error, "선택한 Discord 역할의 멤버 목록 요청에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  }, [handleError, rolesUrl]);

  useEffect(() => {
    if (roleMode) {
      setLoading(true);
      api(rolesUrl).then((data) => {
        setRoles(data);
        if (data.length) selectRole(data[0]);
        else setLoading(false);
      }).catch((error) => {
        handleError(error, "Discord 서버 연결과 Guild ID를 확인해 주세요.");
        setLoading(false);
      });
    } else if (communityValid) {
      loadCommunityMembers();
    } else {
      setMessage("주소에 올바른 guildId 또는 communityId를 입력해 주세요.");
      setLoading(false);
    }
  }, [communityValid, handleError, loadCommunityMembers, roleMode, rolesUrl, selectRole]);

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
  const count = loading ? "불러오는 중..." : roleMode && selectedRole
    ? `${selectedRole.name} (${members.length}명)` : `클랜원 ${members.length}명`;

  return (
    <>
      <AppHeader />
      <main className="page-main compact-main">
        <CommunityBackLink communityId={communityId} />
        <section className="card members-card" aria-labelledby="member-list-title">
          <div className="card-header"><h1 id="member-list-title">{title}</h1><p className="member-count">{count}</p></div>
          {roleMode && roles.length > 0 && (
            <div className="role-tabs" role="tablist" aria-label="Discord 역할">
              {roles.map((role) => (
                <button className="role-tab" type="button" role="tab" key={role.id}
                        aria-selected={selectedRole?.id === role.id} onClick={() => selectRole(role)}>{role.name}</button>
              ))}
            </div>
          )}
          {!roleMode && communityValid && (
            <form className="add-member-form" onSubmit={addMember}>
              <label htmlFor="nickname">새 클랜원 추가</label>
              <input id="nickname" placeholder="클랜원 이름 입력" autoComplete="off" required
                     value={nickname} onChange={(event) => setNickname(event.target.value)} />
              <button type="submit" disabled={saving}>{saving ? "추가 중..." : "추가"}</button>
            </form>
          )}
          {message && <p className="message padded-message" role="alert">{message}</p>}
          {!loading && !message && members.length === 0 && (
            <p className="empty-state">{roleMode ? roles.length ? "이 역할을 가진 사용자가 없습니다." : "@everyone을 제외한 역할이 없습니다." : "등록된 클랜원이 없습니다."}</p>
          )}
          <ul className="member-list">
            {members.map((member) => {
              const name = roleMode ? member.displayName : member.nickname;
              return <li className="member-item" key={member.id ?? member.discordUserId ?? `${name}-${member.username ?? ""}`}><Avatar name={name} /><span>{name}</span></li>;
            })}
          </ul>
        </section>
      </main>
    </>
  );
}
