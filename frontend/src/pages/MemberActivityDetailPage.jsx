import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import { activityStatus, formatGameMode, formatRelativeDays } from "../activityView.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";

export default function MemberActivityDetailPage() {
  const params = new URLSearchParams(window.location.search);
  const communityId = params.get("communityId");
  const memberId = params.get("memberId");
  const validIds = /^\d+$/.test(communityId ?? "") && /^\d+$/.test(memberId ?? "");
  const [community, setCommunity] = useState(null);
  const [activity, setActivity] = useState(null);
  const [loading, setLoading] = useState(validIds);
  const [message, setMessage] = useState(validIds ? "" : "올바른 클랜원을 선택해 주세요.");

  useEffect(() => {
    if (!validIds) return;
    let cancelled = false;
    async function load() {
      setLoading(true);
      try {
        const [dashboard, result] = await Promise.all([
          api(`/api/communities/${encodeURIComponent(communityId)}`),
          api(`/api/communities/${encodeURIComponent(communityId)}/members/${encodeURIComponent(memberId)}/activity`),
        ]);
        if (cancelled) return;
        setCommunity(dashboard);
        setActivity(result);
      } catch (error) {
        if (!redirectToLogin(error) && !cancelled) {
          setMessage(error.status === 404
            ? "클랜원을 찾을 수 없습니다."
            : error.status === 403
            ? "클랜원 활동을 확인할 권한이 없습니다."
            : "배틀그라운드 활동 정보를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [communityId, memberId, validIds]);

  const status = activityStatus(activity?.status);
  const formatPlayedAt = (value) => new Intl.DateTimeFormat("ko-KR", {
    month: "long", day: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(new Date(value));

  return (
    <DashboardLayout active="activity" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content activity-content">
        <a className="activity-back-link" href={`/member-activities.html?communityId=${encodeURIComponent(communityId || "")}`}>
          <span aria-hidden="true">←</span> 클랜원 활동
        </a>

        {loading && <section className="panel activity-loading" role="status">
          <span className="activity-loader" aria-hidden="true" />
          <div><h2>저장된 최근 활동을 불러오고 있습니다...</h2><p>마지막 활동 조회의 게임 내역을 확인합니다.</p></div>
        </section>}
        {message && <p className="message" role="alert">{message}</p>}

        {!loading && activity && <>
          <section className="panel member-activity-profile">
            <div className="member-activity-name">
              <Avatar className="avatar" name={activity.member.discordNickname} />
              <div><p className="eyebrow">Clan member</p><h1>{activity.member.discordNickname}</h1></div>
            </div>
            <dl className="member-activity-facts">
              <div><dt>인게임 닉네임</dt><dd>{activity.member.gameNickname || "확인 필요"}</dd></div>
              <div><dt>활동 상태</dt><dd><span className={`activity-status ${status.tone}`}><b>{status.symbol}</b>{status.label}</span></dd></div>
              <div><dt>최근 클랜 활동</dt><dd>{formatRelativeDays(activity.lastClanActivityAt)}</dd></div>
            </dl>
          </section>

          <aside className="panel current-activity-rule">
            <span className="management-card-icon activity"><Icon name="activity" size={22} /></span>
            <div><h2>현재 활동 기준</h2><p>최근 {activity.rule.activityPeriodDays}일 동안<br />같은 팀에 클랜원이 {activity.rule.minimumClanMembersInRoster}명 이상인 게임</p></div>
          </aside>

          <section className="activity-match-section" aria-labelledby="recent-match-title">
            <div className="activity-match-heading"><div><h2 id="recent-match-title">최근 게임 내역</h2><p>활동 확인 기간에 참가한 게임을 최신순으로 보여줍니다.</p></div><span>{activity.matches.length}게임</span></div>
            {activity.matches.length === 0 ? <div className="panel activity-empty-matches">
              <Icon name="game" size={25} /><h3>표시할 최근 게임이 없습니다.</h3>
              <p>{activity.status === "ACCOUNT_VERIFICATION_REQUIRED" ? "인게임 닉네임과 PUBG 계정을 확인해 주세요." : "활동 확인 기간에 조회된 게임이 없습니다."}</p>
            </div> : <div className="activity-match-list">
              {activity.matches.map((match) => <article className={`panel activity-match-card${match.activityRecognized ? " recognized" : ""}`} key={match.matchId}>
                <div className="activity-match-meta"><time dateTime={match.playedAt}>{formatPlayedAt(match.playedAt)}</time><span>{formatGameMode(match.gameMode)}</span></div>
                <div className="activity-match-team">
                  <span>같이 플레이한 사람</span>
                  <div className="activity-team-player-list">
                    {match.playersInTeam
                      .filter((player) => player.pubgAccountId !== activity.member.pubgAccountId)
                      .map((player, index) => <span key={`${player.pubgAccountId || player.pubgNickname}-${index}`}>
                        <b>{player.pubgNickname || "이름 미확인"}</b>
                        {player.clanMember && <em>클랜원</em>}
                      </span>)}
                    {match.playersInTeam.filter((player) => player.pubgAccountId !== activity.member.pubgAccountId).length === 0
                      && <span><b>없음</b></span>}
                  </div>
                  <small>같은 팀의 클랜원 {match.clanMemberCountInTeam}명 · 본인 포함</small>
                </div>
                <span className={`match-recognition ${match.activityRecognized ? "recognized" : "not-recognized"}`}>
                  {match.activityRecognized ? "✓ 활동 인정" : "활동 인정 안 됨"}
                </span>
              </article>)}
            </div>}
          </section>
        </>}
      </div>
    </DashboardLayout>
  );
}
