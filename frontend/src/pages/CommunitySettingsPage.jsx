import { useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import {
  ACTIVITY_PERIOD_OPTIONS,
  MINIMUM_CLAN_MEMBER_OPTIONS,
  activityRulePayload,
} from "../activityRule.js";
import { loadGameNicknameStatus } from "../gameNicknameStatus.js";

export default function CommunitySettingsPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [community, setCommunity] = useState(null);
  const [roles, setRoles] = useState([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState([]);
  const [nicknameStatus, setNicknameStatus] = useState("loading");
  const [activityRule, setActivityRule] = useState(null);
  const [activityPeriodDays, setActivityPeriodDays] = useState(14);
  const [minimumClanMembersInRoster, setMinimumClanMembersInRoster] = useState(2);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [activitySaving, setActivitySaving] = useState(false);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [success, setSuccess] = useState("");

  const canManage = community?.role === "OWNER" || community?.role === "ADMIN";
  const selected = useMemo(() => new Set(selectedRoleIds), [selectedRoleIds]);
  const activityEndpoint = `/api/communities/${encodeURIComponent(communityId || "")}/activity-rule`;

  useEffect(() => {
    if (!validId) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    async function load() {
      setLoading(true);
      setMessage("");
      try {
        const dashboard = await api(`/api/communities/${encodeURIComponent(communityId)}`);
        if (cancelled) return;
        setCommunity(dashboard);
        const [activityRuleResult, nicknameStatusResult] = await Promise.allSettled([
          api(activityEndpoint),
          loadGameNicknameStatus({
            loadStatus: () => api(`/api/communities/${encodeURIComponent(communityId)}/game-nickname-rule/status`),
            loadRule: () => api(`/api/communities/${encodeURIComponent(communityId)}/game-nickname-rule`),
          }),
        ]);
        if (cancelled) return;
        if (activityRuleResult.status === "rejected") throw activityRuleResult.reason;
        if (nicknameStatusResult.status === "rejected" && nicknameStatusResult.reason?.status === 401) {
          throw nicknameStatusResult.reason;
        }
        const currentActivityRule = activityRuleResult.value;
        setActivityRule(currentActivityRule);
        setNicknameStatus(nicknameStatusResult.status === "fulfilled"
          ? nicknameStatusResult.value.configured ? "configured" : "notConfigured"
          : "error");
        setActivityPeriodDays(currentActivityRule.activityPeriodDays);
        setMinimumClanMembersInRoster(currentActivityRule.minimumClanMembersInRoster);
        if (!dashboard.discordConnected) return;

        const [availableRoles, settings] = await Promise.all([
          api(`/api/communities/${encodeURIComponent(communityId)}/discord/roles`),
          api(`/api/communities/${encodeURIComponent(communityId)}/member-role-settings`),
        ]);
        if (cancelled) return;
        setRoles(availableRoles);
        setSelectedRoleIds(settings.roles.map((role) => role.discordRoleId));
      } catch (error) {
        if (!redirectToLogin(error) && !cancelled) {
          setMessage(error.status === 403
            ? "이 커뮤니티 설정에 접근할 권한이 없습니다."
            : error.message || "커뮤니티 설정을 불러오지 못했습니다.");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [activityEndpoint, communityId, validId]);

  function toggleRole(roleId) {
    setSuccess("");
    setSelectedRoleIds((current) => current.includes(roleId)
      ? current.filter((id) => id !== roleId)
      : [...current, roleId]);
  }

  async function saveSettings() {
    setSaving(true);
    setMessage("");
    setSuccess("");
    let settingsSaved = false;
    try {
      const result = await api(`/api/communities/${encodeURIComponent(communityId)}/member-role-settings`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ discordRoleIds: selectedRoleIds }),
      });
      settingsSaved = true;
      setSelectedRoleIds(result.roles.map((role) => role.discordRoleId));
      if (result.roles.length === 0) {
        setSuccess("클랜원 역할 설정이 저장되었습니다.");
      } else {
        const sync = await api(`/api/communities/${encodeURIComponent(communityId)}/members/sync`, {
          method: "POST",
        });
        setSuccess(`클랜원 역할 설정과 Discord 동기화가 완료되었습니다. 신규 ${sync.createdMembers}명 / 업데이트 ${sync.updatedMembers + sync.reactivatedMembers}명 / 탈퇴 처리 ${sync.leftMembers}명`);
      }
    } catch (error) {
      if (!redirectToLogin(error)) {
        setMessage(settingsSaved
          ? `역할 설정은 저장되었지만 Discord 클랜원 동기화에 실패했습니다. ${error.message || ""}`.trim()
          : error.status === 403
          ? "클랜원 역할 설정을 변경할 관리 권한이 없습니다."
          : error.message || "클랜원 역할 설정을 저장하지 못했습니다.");
      }
    } finally {
      setSaving(false);
    }
  }

  async function saveActivityRule() {
    setActivitySaving(true);
    setMessage("");
    setSuccess("");
    try {
      const payload = activityRulePayload(activityPeriodDays, minimumClanMembersInRoster);
      const saved = await api(activityEndpoint, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      setActivityRule(saved);
      setActivityPeriodDays(saved.activityPeriodDays);
      setMinimumClanMembersInRoster(saved.minimumClanMembersInRoster);
      setSuccess("클랜 활동 규칙이 저장되었습니다.");
    } catch (error) {
      if (!redirectToLogin(error)) {
        setMessage(error.status === 403
          ? "클랜 활동 규칙을 변경할 관리 권한이 없습니다."
          : error.message || "클랜 활동 규칙을 저장하지 못했습니다.");
      }
    } finally {
      setActivitySaving(false);
    }
  }

  return (
    <DashboardLayout active="settings" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content narrow-content">
        <div className="page-heading">
          <p className="eyebrow">Settings</p>
          <h1>커뮤니티 설정</h1>
          <p>클랜 활동 기준과 Discord 연동 방식을 관리합니다.</p>
        </div>

        {loading && <p role="status">설정을 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}

        {!loading && community && <section className="settings-status-grid" aria-label="초기 설정 상태">
          <article className="panel member-role-guide settings-status-card">
            <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
            <div>
              <h2>{selectedRoleIds.length > 0
                ? "Discord 클랜원 역할 설정 완료"
                : "아직 클랜원 역할이 설정되지 않았습니다."}</h2>
              <p>{selectedRoleIds.length > 0
                ? `${selectedRoleIds.length}개의 Discord 역할로 클랜원을 분류하고 있습니다.`
                : "Discord 역할을 설정하면 클랜원을 자동으로 분류할 수 있습니다."}</p>
            </div>
            {community.discordConnected && <a className="secondary-button" href="#role-settings-title">
              {selectedRoleIds.length > 0 ? "클랜원 역할 관리" : "클랜원 역할 설정"}
            </a>}
          </article>

          <article className="panel member-role-guide settings-status-card">
            <span className="management-card-icon activity"><Icon name="game" size={22} /></span>
            <div>
              <h2>{nicknameStatus === "configured"
                ? "인게임 닉네임 설정 완료"
                : nicknameStatus === "error"
                ? "인게임 닉네임 설정 상태를 확인하지 못했습니다."
                : "인게임 닉네임이 설정되지 않았습니다."}</h2>
              <p>{nicknameStatus === "configured"
                ? "클랜원의 인게임 닉네임이 등록되어 있습니다."
                : nicknameStatus === "error"
                ? "잠시 후 다시 시도해 주세요."
                : "활동 정보를 조회하려면 클랜원의 인게임 닉네임을 먼저 설정해야 합니다."}</p>
            </div>
            <a className="secondary-button" href={`/game-nickname-settings.html?communityId=${encodeURIComponent(communityId)}`}>
              {nicknameStatus === "configured" ? "인게임 닉네임 관리" : "인게임 닉네임 설정"}
            </a>
          </article>
        </section>}

        {!loading && community && activityRule && (
          <section className="panel activity-rule-panel" aria-labelledby="activity-rule-title">
            <div className="settings-section-heading">
              <span className="management-card-icon activity"><Icon name="activity" size={22} /></span>
              <div>
                <h2 id="activity-rule-title">클랜 활동 규칙</h2>
                <p>클랜원의 배틀그라운드 활동을 어떤 기준으로 인정할지 설정합니다.</p>
              </div>
            </div>

            <div className="activity-rule-fields">
              <label className="activity-rule-field" htmlFor="activity-period-days">
                <span>활동 확인 기간</span>
                <select id="activity-period-days" value={activityPeriodDays}
                        disabled={!canManage || activitySaving}
                        onChange={(event) => {
                          setActivityPeriodDays(Number(event.target.value));
                          setSuccess("");
                        }}>
                  {ACTIVITY_PERIOD_OPTIONS.map((days) => (
                    <option value={days} key={days}>{days}일</option>
                  ))}
                </select>
                <small>최근 {activityPeriodDays}일 동안의 경기를 확인합니다.</small>
              </label>

              <label className="activity-rule-field" htmlFor="minimum-clan-members">
                <span>활동 인정 인원</span>
                <select id="minimum-clan-members" value={minimumClanMembersInRoster}
                        disabled={!canManage || activitySaving}
                        onChange={(event) => {
                          setMinimumClanMembersInRoster(Number(event.target.value));
                          setSuccess("");
                        }}>
                  {MINIMUM_CLAN_MEMBER_OPTIONS.map((members) => (
                    <option value={members} key={members}>{members}명</option>
                  ))}
                </select>
                <small>같은 팀에 본인을 포함한 클랜원이 {minimumClanMembersInRoster}명 이상이면 활동으로 인정합니다.</small>
              </label>
            </div>

            <div className="activity-rule-example">
              <span>예시</span>
              <strong>sa-gwa + 절미</strong>
              <p>같은 팀으로 플레이 · 활동 인정 <b aria-label="인정">✓</b></p>
            </div>

            {!canManage && <p className="settings-notice">OWNER 또는 ADMIN만 이 설정을 변경할 수 있습니다.</p>}
            <div className="settings-actions">
              <span>{activityRule.gameName}에 적용</span>
              <button type="button" disabled={!canManage || activitySaving} onClick={saveActivityRule}>
                {activitySaving ? "저장 중..." : "활동 규칙 저장"}
              </button>
            </div>
          </section>
        )}

        {!loading && community && !community.discordConnected && (
          <section className="panel role-settings-panel">
            <div className="settings-section-heading">
              <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
              <div><h2>Discord 연동</h2><p>역할 설정을 사용하려면 Discord 서버를 먼저 연결해 주세요.</p></div>
            </div>
            <a className="button-link" href={`/discord-connect.html?communityId=${encodeURIComponent(communityId)}`}>
              Discord 연결하기 <Icon name="arrow" size={17} />
            </a>
          </section>
        )}

        {!loading && community?.discordConnected && (
          <section className="panel role-settings-panel" aria-labelledby="role-settings-title">
            <div className="connected-guild-row">
              <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
              <div><p>연결된 서버</p><strong>{community.discordGuildName || community.discordGuildId}</strong></div>
              <span className="status-badge connected"><span aria-hidden="true" />연결됨</span>
            </div>

            <div className="role-settings-copy">
              <h2 id="role-settings-title">클랜원 역할 설정</h2>
              <p>Discord 서버에서 어떤 역할을 GuildUp의 클랜원으로 인식할지 선택하세요.</p>
            </div>

            {roles.length > 0 ? (
              <div className="role-check-list">
                {roles.map((role) => (
                  <label className={`role-check-item${selected.has(role.id) ? " is-selected" : ""}`} key={role.id}>
                    <input type="checkbox" checked={selected.has(role.id)} disabled={!canManage || saving}
                           onChange={() => toggleRole(role.id)} />
                    <span className="custom-checkbox"><Icon name="check" size={15} /></span>
                    <span className="role-check-name">{role.name}</span>
                    <span className="role-check-id">{role.id}</span>
                  </label>
                ))}
              </div>
            ) : (
              <p className="role-settings-empty">선택할 수 있는 Discord 역할이 없습니다.</p>
            )}

            {!canManage && <p className="settings-notice">OWNER 또는 ADMIN만 이 설정을 변경할 수 있습니다.</p>}
            <div className="settings-actions">
              <span>{selectedRoleIds.length}개 역할 선택됨</span>
              <button type="button" disabled={!canManage || saving} onClick={saveSettings}>
                {saving ? "저장 중..." : "설정 저장"}
              </button>
            </div>
          </section>
        )}

        {success && <p className="success-message page-success" role="status">{success}</p>}

      </div>
    </DashboardLayout>
  );
}
