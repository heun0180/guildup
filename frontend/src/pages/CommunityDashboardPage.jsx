import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import CommunityNewsSummary from "../components/CommunityNewsSummary.jsx";
import { canManageCommunity } from "../communityAccess.js";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function CommunityDashboardPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const { community } = useCommunity();
  const [memberCount, setMemberCount] = useState(null);
  const [roleCount, setRoleCount] = useState(null);
  const [attendance, setAttendance] = useState(null);
  const [checkingAttendance, setCheckingAttendance] = useState(false);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;

    async function loadDashboard() {
      try {
        const dashboard = community;
        if (cancelled) return;

        api(`/api/communities/${encodeURIComponent(communityId)}/attendance/me`)
          .then((status) => { if (!cancelled) setAttendance(status); })
          .catch((error) => {
            if (cancelled) return;
            if (error.status === 409) {
              setAttendance({ unavailable: true });
            } else if (!redirectToLogin(error)) {
              setMessage(error.message);
            }
          });

        const requests = [api(`/api/communities/${encodeURIComponent(communityId)}/members`)];
        if (dashboard.discordConnected && canManageCommunity(dashboard.role)) {
          requests.push(api(`/api/communities/${encodeURIComponent(communityId)}/discord/roles`));
        }
        const [membersResult, rolesResult] = await Promise.allSettled(requests);
        if (cancelled) return;
        if (membersResult.status === "fulfilled") setMemberCount(membersResult.value.length);
        if (rolesResult?.status === "fulfilled") setRoleCount(rolesResult.value.length);
      } catch (error) {
        if (!redirectToLogin(error) && !cancelled) {
          setMessage(error.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다." : error.message);
        }
      }
    }

    loadDashboard();
    return () => { cancelled = true; };
  }, [community, communityId, validId]);

  const discordName = community?.discordGuildName || community?.discordGuildId;
  const canManage = canManageCommunity(community?.role);

  async function checkAttendance() {
    if (checkingAttendance || attendance?.attended) return;
    setCheckingAttendance(true);
    setMessage("");
    try {
      const result = await api(`/api/communities/${encodeURIComponent(communityId)}/attendance`, {
        method: "POST",
      });
      setAttendance({
        attended: true,
        attendanceDate: result.attendanceDate,
        currentScore: result.currentScore,
        justAttended: result.scoreAdded === 1,
      });
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message || "출석 체크에 실패했습니다.");
    } finally {
      setCheckingAttendance(false);
    }
  }

  return (
    <DashboardLayout active="dashboard" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content">
        <div className="page-heading is-compact">
          <p className="eyebrow">{community?.name}</p>
          <h1>커뮤니티 대시보드</h1>
          {community?.gameName && <span className="page-context-badge">{community.gameName}</span>}
        </div>

        {!community && !message && <p className="panel page-state" role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}

        {community && (
          <>
            <section className="summary-grid" aria-label="커뮤니티 요약">
              <article className="summary-card">
                <span className="summary-icon"><Icon name="users" size={21} /></span>
                <div><p>클랜원</p><strong>{memberCount === null ? "-" : `${memberCount}명`}</strong></div>
              </article>
              <article className="summary-card">
                <span className={`summary-icon${community.discordConnected ? " success" : ""}`}><Icon name="discord" size={21} /></span>
                <div><p>Discord</p><strong>{community.discordConnected ? "연결됨" : "연결 안 됨"}</strong></div>
              </article>
              {canManage && <article className="summary-card">
                <span className="summary-icon"><Icon name="link" size={21} /></span>
                <div><p>Discord 역할</p><strong>{roleCount === null ? "-" : `${roleCount}개`}</strong></div>
              </article>}
            </section>

            {attendance && <section className={`panel attendance-card dashboard-attendance${attendance.attended ? " is-complete" : ""}`}>
              <div className="attendance-copy">
                <span className="attendance-icon"><Icon name={attendance.attended ? "check" : "calendar"} size={23} /></span>
                <div>
                  <h2>{attendance.attended ? "오늘 출석 완료" : "오늘 출석 체크"}</h2>
                  <p>{attendance.unavailable
                    ? "로그인한 Discord 계정과 클랜원 정보가 연결되면 출석할 수 있습니다."
                    : attendance.attended
                    ? attendance.justAttended ? "오늘 +1점을 받았습니다." : `현재 점수 ${attendance.currentScore}점`
                    : "버튼을 누르면 오늘의 활동 점수 +1점을 받습니다."}</p>
                </div>
              </div>
              <div className="attendance-actions">
                {!attendance.attended && !attendance.unavailable && <button type="button" onClick={checkAttendance} disabled={checkingAttendance}>
                  <Icon name="check" size={18} />{checkingAttendance ? "요청 중..." : "출석 체크 +1점"}
                </button>}
                <a className="secondary-button" href={`/rankings.html?communityId=${encodeURIComponent(communityId)}`}>
                  전체 랭킹 <Icon name="arrow" size={16} />
                </a>
              </div>
            </section>}

            <CommunityNewsSummary communityId={communityId} />

            <div className="section-heading">
              <div><h2>{canManage ? "관리" : "커뮤니티"}</h2></div>
            </div>
            <section className="management-grid">
              {canManage && <article className="management-card">
                <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
                <div className="management-card-body">
                  <div className="management-title-row">
                    <h2>Discord 서버</h2>
                    <span className={`status-badge ${community.discordConnected ? "connected" : "disconnected"}`}>
                      <span aria-hidden="true" />{community.discordConnected ? "연결됨" : "연결 안 됨"}
                    </span>
                  </div>
                  <p>{community.discordConnected ? discordName : "Discord 서버가 연결되어 있지 않습니다."}</p>
                  <a className="secondary-button" href={community.discordConnected
                    ? `/members.html?communityId=${encodeURIComponent(communityId)}&discordRoles=true`
                    : `/discord-connect.html?communityId=${encodeURIComponent(communityId)}`}>
                    {community.discordConnected ? "Discord 관리" : "Discord 연결하기"}<Icon name="arrow" size={16} />
                  </a>
                </div>
              </article>}
              <article className="management-card">
                <span className="management-card-icon"><Icon name="users" size={22} /></span>
                <div className="management-card-body">
                  <div className="management-title-row"><h2>클랜원</h2></div>
                  {canManage && <p>Discord 역할과 동기화된 클랜원입니다.</p>}
                  <a className="secondary-button" href={`/members.html?communityId=${encodeURIComponent(communityId)}`}>
                    클랜원 보기 <Icon name="arrow" size={16} />
                  </a>
                </div>
              </article>
            </section>
          </>
        )}
      </div>
    </DashboardLayout>
  );
}
