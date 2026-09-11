import { useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import {
  formatVoiceDateTime,
  formatVoiceDuration,
  loadVoiceActivityOnce,
  voiceActivityView,
} from "../voiceActivityView.js";

export default function DiscordVoiceActivityPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const encodedCommunityId = encodeURIComponent(communityId || "");
  const [members, setMembers] = useState([]);
  const [loading, setLoading] = useState(validId);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [disconnected, setDisconnected] = useState(false);
  const [selectedMember, setSelectedMember] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailMessage, setDetailMessage] = useState("");

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;
    loadVoiceActivityOnce(encodedCommunityId, () =>
      api(`/api/communities/${encodedCommunityId}/discord/voice-activity`))
      .then((data) => { if (!cancelled) setMembers(data); })
      .catch((error) => {
        if (redirectToLogin(error) || cancelled) return;
        if (error.status === 404) setDisconnected(true);
        else if (error.status === 403) setMessage("음성 활동은 커뮤니티 운영진과 관리자만 확인할 수 있습니다.");
        else setMessage("Discord 음성 활동을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [encodedCommunityId, validId]);

  const view = useMemo(() => voiceActivityView(members), [members]);

  async function openDetail(member) {
    setSelectedMember(member);
    setDetail(null);
    setDetailMessage("");
    setDetailLoading(true);
    try {
      setDetail(await api(
        `/api/communities/${encodedCommunityId}/discord/voice-activity/members/${member.communityMemberId}`,
      ));
    } catch (error) {
      if (!redirectToLogin(error)) setDetailMessage("클랜원의 음성 활동 상세를 불러오지 못했습니다.");
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    setSelectedMember(null);
    setDetail(null);
    setDetailMessage("");
  }

  return (
    <DashboardLayout active="integrations" communityId={communityId} loadCommunity={false}>
      <div className="dashboard-content voice-activity-content">
        <div className="page-heading">
          <p className="eyebrow">Discord</p>
          <h1>Discord 음성 활동</h1>
          <p>최근 14일 동안의 Discord 음성 채널 활동입니다.</p>
        </div>

        {loading && <section className="panel activity-loading" role="status">
          <span className="activity-loader" aria-hidden="true" />
          <div><strong>음성 활동을 불러오는 중입니다.</strong><p>저장된 최근 14일 기록을 확인하고 있습니다.</p></div>
        </section>}
        {message && <p className="message" role="alert">{message}</p>}
        {!loading && disconnected && <section className="panel voice-setup-guide">
          <span className="management-card-icon"><Icon name="discord" /></span>
          <div><h2>Discord 서버가 연결되지 않았습니다.</h2><p>먼저 커뮤니티에 Discord 서버를 연결해 주세요.</p></div>
          <a className="button-link" href={`/discord-connect.html?communityId=${encodedCommunityId}`}>Discord 연결</a>
        </section>}
        {!loading && !disconnected && !message && !view.hasDiscordAccounts && view.hasMembers && (
          <section className="member-role-guide">
            <span className="management-card-icon"><Icon name="settings" /></span>
            <div><h2>연결된 Discord 클랜원 계정이 없습니다.</h2><p>클랜원 역할 설정과 동기화를 먼저 확인해 주세요.</p></div>
            <a className="secondary-button" href={`/community-settings.html?communityId=${encodedCommunityId}`}>설정으로 이동</a>
          </section>
        )}
        {!loading && !disconnected && !message && view.hasMembers && !view.hasActivity && (
          <p className="panel voice-empty-notice">최근 14일 동안 기록된 Discord 음성 활동이 없습니다.</p>
        )}
        {!loading && !disconnected && !message && !view.hasMembers && (
          <p className="panel empty-state">현재 등록된 ACTIVE 클랜원이 없습니다.</p>
        )}
        {!loading && !disconnected && !message && view.hasMembers && <section className="panel voice-activity-panel">
          <div className="voice-panel-heading">
            <div><h2>클랜원별 활동</h2><p>현재 접속 {view.connectedCount}명 · 페이지를 연 시점 기준</p></div>
          </div>
          <div className="member-table-wrap">
            <table className="member-table voice-activity-table">
              <thead><tr><th>클랜원</th><th>음성 활동시간</th><th>최근 음성 접속</th></tr></thead>
              <tbody>{view.rows.map((member) => <tr key={member.communityMemberId}>
                <td><button className="voice-member-button" type="button" onClick={() => openDetail(member)}>
                  <Avatar name={member.nickname} /><span><strong>{member.nickname}</strong>{!member.discordUserId && <small>Discord 계정 미연결</small>}</span>
                </button></td>
                <td>{formatVoiceDuration(member.totalSeconds)}</td>
                <td>{member.currentlyConnected
                  ? <span className="status-badge connected">접속 중</span>
                  : formatVoiceDateTime(member.lastJoinedAt)}</td>
              </tr>)}</tbody>
            </table>
          </div>
        </section>}
      </div>

      {selectedMember && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
        if (event.target === event.currentTarget) closeDetail();
      }}>
        <section className="voice-detail-modal" role="dialog" aria-modal="true" aria-labelledby="voice-detail-title">
          <div className="voice-detail-heading">
            <div><p className="eyebrow">최근 14일 음성 활동</p><h2 id="voice-detail-title">{selectedMember.nickname}</h2></div>
            <button type="button" className="voice-modal-close" onClick={closeDetail} aria-label="닫기">×</button>
          </div>
          {detailLoading && <p className="empty-state" role="status">상세 활동을 불러오는 중입니다.</p>}
          {detailMessage && <p className="message" role="alert">{detailMessage}</p>}
          {detail && <>
            <div className="voice-detail-total"><span>누적 활동시간</span><strong>{formatVoiceDuration(detail.totalSeconds)}</strong></div>
            <h3>최근 활동</h3>
            {detail.sessions.length === 0 && <p className="empty-state">최근 14일 동안 기록된 음성 활동이 없습니다.</p>}
            <div className="voice-session-list">{detail.sessions.map((session, index) => <article key={`${session.channelId}-${session.joinedAt}-${index}`}>
              <div><strong>{session.channelName}</strong>{session.currentlyConnected && <span className="status-badge connected">접속 중</span>}</div>
              <p>{formatVoiceDateTime(session.joinedAt)} ~ {session.currentlyConnected ? "접속 중" : formatVoiceDateTime(session.leftAt)}</p>
              <b>{formatVoiceDuration(session.durationSeconds)}</b>
            </article>)}</div>
          </>}
        </section>
      </div>}
    </DashboardLayout>
  );
}
