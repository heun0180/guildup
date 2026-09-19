import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { ACTIVITY_PERIOD_OPTIONS, MINIMUM_CLAN_MEMBER_OPTIONS, activityRulePayload } from "../activityRule.js";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function ActivityRuleSettingsPage() {
  const { community } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const communityGameId = new URLSearchParams(window.location.search).get("communityGameId");
  const encodedId = encodeURIComponent(communityId || "");
  const endpoint = `/api/communities/${encodedId}/games/${encodeURIComponent(communityGameId || "")}/activity-rule`;
  const [rule, setRule] = useState(null);
  const [periodDays, setPeriodDays] = useState(14);
  const [minimumMembers, setMinimumMembers] = useState(2);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [success, setSuccess] = useState("");

  useEffect(() => {
    let cancelled = false;
    api(endpoint).then((result) => {
      if (cancelled) return;
      setRule(result); setPeriodDays(result.activityPeriodDays); setMinimumMembers(result.minimumClanMembersInRoster);
    }).catch((error) => { if (!redirectToLogin(error) && !cancelled) setMessage(error.message || "클랜 활동 규칙을 불러오지 못했습니다."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [endpoint]);

  async function save() {
    setSaving(true); setMessage(""); setSuccess("");
    try {
      const saved = await api(endpoint, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(activityRulePayload(periodDays, minimumMembers)) });
      setRule(saved); setPeriodDays(saved.activityPeriodDays); setMinimumMembers(saved.minimumClanMembersInRoster); setSuccess("클랜 활동 규칙이 저장되었습니다.");
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message || "클랜 활동 규칙을 저장하지 못했습니다."); }
    finally { setSaving(false); }
  }

  return <DashboardLayout active="settings" communityId={communityId} community={community}><div className="dashboard-content narrow-content">
    <div className="page-heading settings-page-heading"><div><p className="eyebrow">Activity rule</p><h1>클랜 활동 규칙</h1><p>클랜원의 게임 활동을 인정하는 기준을 설정합니다.</p></div><a className="secondary-button" href={`/community-settings.html?communityId=${encodedId}`}>설정 목록</a></div>
    {loading && <p className="panel page-state" role="status">활동 규칙을 불러오는 중입니다.</p>}{message && <p className="message" role="alert">{message}</p>}{success && <p className="success-message page-success" role="status">{success}</p>}
    {!loading && rule && <section className="panel activity-rule-panel">
      <div className="settings-section-heading"><span className="management-card-icon activity"><Icon name="activity" size={22} /></span><div><h2>{rule.gameName} 활동 기준</h2><p>기간과 함께 플레이한 클랜원 수를 기준으로 활동을 판정합니다.</p></div></div>
      <div className="activity-rule-fields"><label className="activity-rule-field" htmlFor="activity-period-days"><span>활동 확인 기간</span><select id="activity-period-days" value={periodDays} disabled={saving} onChange={(event) => setPeriodDays(Number(event.target.value))}>{ACTIVITY_PERIOD_OPTIONS.map((days) => <option value={days} key={days}>{days}일</option>)}</select><small>최근 {periodDays}일 동안의 경기를 확인합니다.</small></label><label className="activity-rule-field" htmlFor="minimum-clan-members"><span>활동 인정 인원</span><select id="minimum-clan-members" value={minimumMembers} disabled={saving} onChange={(event) => setMinimumMembers(Number(event.target.value))}>{MINIMUM_CLAN_MEMBER_OPTIONS.map((members) => <option value={members} key={members}>{members}명</option>)}</select><small>본인을 포함한 클랜원이 {minimumMembers}명 이상이면 인정합니다.</small></label></div>
      <div className="activity-rule-example"><span>적용 예시</span><strong>sa-gwa + 절미</strong><p>같은 팀으로 플레이 · 활동 인정 <b aria-label="인정">✓</b></p></div>
      <div className="settings-actions"><span>{rule.gameName}에 적용</span><button type="button" disabled={saving} onClick={save}>{saving ? "저장 중..." : "활동 규칙 저장"}</button></div>
    </section>}
  </div></DashboardLayout>;
}
