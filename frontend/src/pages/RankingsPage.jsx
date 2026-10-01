import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { shiftedRankingQuery } from "../rankingView.js";
import HelpLink from "../components/HelpLink.jsx";

export default function RankingsPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [selection, setSelection] = useState("");
  const rankingUrl = `/api/communities/${encodeURIComponent(communityId)}/rankings${selection ? `?${selection}` : ""}`;
  const [attendance, setAttendance] = useState(null);
  const [rankingData, setRankingData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState("");
  const [attendanceNotice, setAttendanceNotice] = useState("");
  const [justAttended, setJustAttended] = useState(false);

  const handleError = useCallback((error, fallback) => {
    if (!redirectToLogin(error)) {
      setMessage(error.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다." : error.message || fallback);
    }
  }, []);

  const load = useCallback(async () => {
    if (!validId) {
      setMessage("올바른 커뮤니티를 선택해 주세요.");
      setLoading(false);
      return;
    }
    setLoading(true);
    setMessage("");
    setAttendanceNotice("");
    try {
      const [attendanceResult, rankingsResult] = await Promise.allSettled([
        api(`/api/communities/${encodeURIComponent(communityId)}/attendance/me`),
        api(rankingUrl),
      ]);
      if (rankingsResult.status === "rejected") throw rankingsResult.reason;
      setRankingData(rankingsResult.value);
      if (attendanceResult.status === "fulfilled") {
        setAttendance(attendanceResult.value);
      } else if (attendanceResult.reason?.status === 409) {
        setAttendance(null);
        setAttendanceNotice(attendanceResult.reason.message);
      } else {
        throw attendanceResult.reason;
      }
    } catch (error) {
      handleError(error, "활동 랭킹을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, [communityId, handleError, validId, rankingUrl]);

  useEffect(() => { load(); }, [load]);

  async function checkAttendance() {
    if (checking || attendance?.attended) return;
    setChecking(true);
    setMessage("");
    try {
      const result = await api(`/api/communities/${encodeURIComponent(communityId)}/attendance`, {
        method: "POST",
      });
      setAttendance({
        attended: true,
        attendanceDate: result.attendanceDate,
        currentScore: result.currentScore,
      });
      setJustAttended(result.scoreAdded === 1);
      setRankingData(await api(rankingUrl));
    } catch (error) {
      handleError(error, "출석 체크에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setChecking(false);
    }
  }

  const myRanking = rankingData?.myRanking;
  const period = rankingData?.period;

  function movePeriod(direction) {
    setSelection(shiftedRankingQuery(period, direction));
  }

  return (
    <DashboardLayout active="rankings" communityId={communityId} onError={setMessage}>
      <div className="dashboard-content ranking-content">
        <div className="page-heading is-compact feature-page-heading">
          <div><p className="eyebrow">Activity Ranking</p><h1>활동 랭킹</h1></div>
          <HelpLink href="/help/ranking" />
        </div>

        {message && <p className="message" role="alert">{message}</p>}
        {loading && <section className="panel ranking-loading" aria-live="polite">랭킹을 불러오는 중입니다.</section>}

        {!loading && rankingData && <>
          {period && <section className="panel ranking-period" aria-label="랭킹 집계 기간">
            <div><h2>{period.title}</h2>{period.startDate && <p>{period.startDate.replaceAll("-", ".")} ~ {period.endDate.replaceAll("-", ".")} · Asia/Seoul</p>}</div>
            {period.periodType !== "ALL_TIME" && <nav aria-label="과거 랭킹 조회">
              <button type="button" className="secondary-button" disabled={checking || period.year === 1 && (period.month === 1 || period.quarter === 1)} onClick={() => movePeriod(-1)}>이전</button>
              <button type="button" className="secondary-button" disabled={period.current || checking} onClick={() => movePeriod(1)}>다음</button>
              {!period.current && <button type="button" className="secondary-button" disabled={checking} onClick={() => setSelection("")}>현재 기간</button>}
            </nav>}
          </section>}
          <section className="ranking-summary-grid" aria-label="내 활동 점수">
            <div className="panel ranking-summary-card">
              <span>내 순위</span>
              <strong>{myRanking?.rank == null ? "-" : `${myRanking.rank}위`}</strong>
            </div>
            <div className="panel ranking-summary-card">
              <span>내 점수</span>
              <strong>{myRanking?.score ?? 0}점</strong>
            </div>
          </section>

          {attendanceNotice && <p className="message" role="status">{attendanceNotice}</p>}
          {attendance && <section className={`panel attendance-card${attendance.attended ? " is-complete" : ""}`}>
            <div className="attendance-copy">
              <span className="attendance-icon" aria-hidden="true"><Icon name={attendance.attended ? "check" : "calendar"} size={23} /></span>
              <div>
                <div className="inline-help-heading"><h2>{attendance.attended ? "오늘 출석 완료" : "오늘의 출석"}</h2><HelpLink href="/help/attendance" /></div>
                <p>{attendance.attended
                  ? justAttended ? "오늘 +1점을 받았습니다." : "오늘의 활동 점수를 이미 받았습니다."
                  : "오늘 출석하고 활동 점수를 받아보세요."}</p>
              </div>
            </div>
            {attendance.attended
              ? <strong className="attendance-score">누적 점수 {attendance.currentScore}점</strong>
              : <button type="button" onClick={checkAttendance} disabled={checking}>
                  <Icon name="check" size={18} />{checking ? "요청 중..." : "출석 체크 +1점"}
                </button>}
          </section>}

          <section className="panel ranking-panel" aria-labelledby="ranking-list-title">
            <div className="ranking-panel-heading">
              <div><h2 id="ranking-list-title">{period?.title ?? "전체 랭킹"}</h2><p>ACTIVE 클랜원 {rankingData.rankings.length}명</p></div>
              <span>Asia/Seoul 기준</span>
            </div>
            {rankingData.rankings.length === 0
              ? <p className="empty-state">등록된 활성 클랜원이 없습니다.</p>
              : <ol className="ranking-list">
                  {rankingData.rankings.map((entry) => (
                    <li className={`ranking-row${entry.me ? " is-me" : ""}${entry.rank <= 3 ? " is-top" : ""}`} key={entry.memberId}>
                      <span className="ranking-position" aria-label={`${entry.rank}위`}>
                        {entry.rank <= 3 && <span aria-hidden="true">{["🥇", "🥈", "🥉"][entry.rank - 1]}</span>}
                        <b>{entry.rank}</b>
                      </span>
                      <span className="ranking-name" title={entry.nickname}>{entry.nickname}
                        <span className="ranking-breakdown">출석 {entry.attendanceScore}점 · 킬내기 {entry.killCompetitionScore}점</span>
                      </span>
                      <span>{entry.me && <span className="ranking-me-badge">나</span>}</span>
                      <strong className="ranking-score">{entry.score}점</strong>
                    </li>
                  ))}
                </ol>}
          </section>
        </>}
      </div>
    </DashboardLayout>
  );
}
