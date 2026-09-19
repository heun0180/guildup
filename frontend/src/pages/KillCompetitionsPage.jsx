import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import { formatDateTime, remainingLabel, statusLabel, statusMessage } from "../killCompetitionView.js";

const basePath = (communityId, communityGameId) => `/api/communities/${encodeURIComponent(communityId)}/games/${encodeURIComponent(communityGameId)}/kill-competitions`;

export default function KillCompetitionsPage() {
  const navigate = useNavigate();
  const params = new URLSearchParams(window.location.search);
  const communityId = params.get("communityId");
  const communityGameId = params.get("communityGameId");
  const competitionId = params.get("competitionId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [items, setItems] = useState([]);
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [showCreate, setShowCreate] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [teamCount, setTeamCount] = useState(2);
  const [assignments, setAssignments] = useState({});
  const [communityMembers, setCommunityMembers] = useState([]);

  const handleError = useCallback((error, fallback) => {
    if (!redirectToLogin(error)) setMessage(error.message || fallback);
  }, []);

  const load = useCallback(async () => {
    if (!validId) { setMessage("올바른 커뮤니티를 선택해 주세요."); setLoading(false); return; }
    setLoading(true); setMessage("");
    try {
      if (competitionId) setDetail(await api(`${basePath(communityId, communityGameId)}/${encodeURIComponent(competitionId)}`));
      else setItems(await api(basePath(communityId, communityGameId)));
    } catch (error) { handleError(error, "킬내기를 불러오지 못했습니다."); }
    finally { setLoading(false); }
  }, [communityId, communityGameId, competitionId, handleError, validId]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (detail?.status !== "RESULT_PENDING") return undefined;
    const timer = window.setInterval(load, 30_000);
    return () => window.clearInterval(timer);
  }, [detail?.status, load]);
  useEffect(() => {
    if (!detail?.creatorView) return;
    api(`/api/communities/${encodeURIComponent(communityId)}/members`)
      .then(setCommunityMembers).catch((error) => handleError(error, "클랜원 목록을 불러오지 못했습니다."));
  }, [communityId, detail?.creatorView, handleError]);
  useEffect(() => {
    if (!detail?.serverTime) return undefined;
    const offset = new Date(detail.serverTime).getTime() - Date.now();
    setNow(Date.now() + offset);
    const timer = window.setInterval(() => setNow(Date.now() + offset), 1000);
    return () => window.clearInterval(timer);
  }, [detail?.serverTime]);
  useEffect(() => {
    if (!detail || detail.gameMode === "SOLO" || detail.status !== "READY") return;
    const count = Math.max(detail.teams.length || 2, 2);
    setTeamCount(count);
    const next = {};
    detail.participants.forEach((participant, index) => {
      const existing = detail.teams.find((team) => team.participantIds.includes(participant.participantId));
      next[participant.participantId] = existing?.displayOrder ?? ((index % count) + 1);
    });
    setAssignments(next);
  }, [detail?.id, detail?.status, detail?.teams.length, detail?.participants.length]);

  async function mutate(path, options = { method: "POST" }) {
    if (busy) return;
    setBusy(true); setMessage("");
    try { setDetail(await api(`${basePath(communityId, communityGameId)}/${detail.id}${path}`, options)); }
    catch (error) { handleError(error, "요청을 처리하지 못했습니다."); }
    finally { setBusy(false); }
  }

  async function createCompetition(event) {
    event.preventDefault(); if (busy) return;
    const form = new FormData(event.currentTarget);
    const localEnd = form.get("endsAt");
    setBusy(true); setMessage("");
    try {
      const created = await api(basePath(communityId, communityGameId), {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: form.get("title"), gameMode: form.get("gameMode"), endsAt: new Date(localEnd).toISOString() }),
      });
      navigate(`/kill-competitions.html?communityId=${encodeURIComponent(communityId)}&communityGameId=${encodeURIComponent(communityGameId)}&competitionId=${created.id}`);
    } catch (error) { handleError(error, "킬내기를 만들지 못했습니다."); setBusy(false); }
  }

  function saveTeams() {
    mutate("/teams", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({
      teamCount: Number(teamCount), assignments: detail.participants.map((participant) => ({
        participantId: participant.participantId, teamNumber: Number(assignments[participant.participantId]),
      })),
    }) });
  }

  return (
    <DashboardLayout active="kill-competitions" communityId={communityId} onError={setMessage}>
      <div className="dashboard-content kill-content">
        {!competitionId ? <CompetitionList items={items} loading={loading} showCreate={showCreate}
          setShowCreate={setShowCreate} createCompetition={createCompetition} busy={busy} message={message} />
          : <CompetitionDetail detail={detail} loading={loading} message={message} busy={busy} now={now}
              communityId={communityId} mutate={mutate} teamCount={teamCount} setTeamCount={setTeamCount}
              assignments={assignments} setAssignments={setAssignments} saveTeams={saveTeams}
              communityMembers={communityMembers} />}
      </div>
    </DashboardLayout>
  );
}

function CompetitionList({ items, loading, showCreate, setShowCreate, createCompetition, busy, message }) {
  const defaultEnd = useMemo(() => {
    const value = new Date(Date.now() + 2 * 60 * 60 * 1000); value.setSeconds(0, 0);
    const local = new Date(value.getTime() - value.getTimezoneOffset() * 60_000);
    return local.toISOString().slice(0, 16);
  }, []);
  return <>
    <div className="page-heading is-compact kill-heading"><div><p className="eyebrow">Clan Challenge</p><h1>킬내기</h1></div>
      <button type="button" onClick={() => setShowCreate((value) => !value)}><Icon name="plus" size={18} />킬내기 만들기</button>
    </div>
    {message && <p className="message" role="alert">{message}</p>}
    {showCreate && <form className="panel kill-create-form" onSubmit={createCompetition}>
      <div><h2>새 킬내기</h2><p>시작 시각은 생성자가 시작 버튼을 누르는 순간 서버에 기록됩니다.</p></div>
      <label><span>제목</span><input name="title" maxLength="100" required placeholder="예: 치즈 주말 킬내기" /></label>
      <label><span>게임 방식</span><select name="gameMode" defaultValue="SQUAD"><option>SOLO</option><option>DUO</option><option>SQUAD</option></select></label>
      <label><span>종료 날짜 및 시각</span><input name="endsAt" type="datetime-local" defaultValue={defaultEnd} required /></label>
      <div className="kill-form-actions"><button type="button" className="secondary-button" onClick={() => setShowCreate(false)}>취소</button>
        <button type="submit" disabled={busy}>{busy ? "만드는 중..." : "만들기"}</button></div>
    </form>}
    {loading ? <section className="panel kill-empty">킬내기를 불러오는 중입니다.</section>
      : items.length === 0 ? <section className="panel kill-empty"><Icon name="target" size={34} /><h2>아직 킬내기가 없습니다.</h2><p>첫 킬내기를 만들어 클랜원과 경쟁해 보세요.</p></section>
      : <div className="kill-card-grid">{items.map((item) => <a className="panel kill-card" key={item.id}
          href={`/kill-competitions.html?communityId=${encodeURIComponent(new URLSearchParams(location.search).get("communityId"))}&communityGameId=${encodeURIComponent(new URLSearchParams(location.search).get("communityGameId"))}&competitionId=${item.id}`}>
        <div className="kill-card-top"><span className={`kill-status status-${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span><b>{item.gameMode}</b></div>
        <h2>{item.title}</h2><dl><div><dt>참가</dt><dd>{item.participantCount}명</dd></div><div><dt>생성자</dt><dd>{item.creatorNickname}</dd></div>
          <div><dt>종료 예정</dt><dd>{formatDateTime(item.endsAt)}</dd></div></dl>
      </a>)}</div>}
  </>;
}

function CompetitionDetail({ detail, loading, message, busy, now, communityId, mutate, teamCount, setTeamCount,
  assignments, setAssignments, saveTeams, communityMembers }) {
  if (loading && !detail) return <section className="panel kill-empty">킬내기 상세 정보를 불러오는 중입니다.</section>;
  if (!detail) return <>{message && <p className="message">{message}</p>}</>;
  if (detail.status === "IN_PROGRESS" && now >= new Date(detail.endsAt).getTime()) {
    detail = { ...detail, status: "ENDED" };
  }
  const pending = detail.participants.filter((participant) => participant.participationStatus === "PENDING");
  const approved = detail.participants.filter((participant) => participant.participationStatus === "APPROVED");
  const canInterim = detail.status === "IN_PROGRESS" && approved.some((participant) => participant.participantId === detail.myParticipantId);
  const canChangeRoster = ["RECRUITING", "READY", "IN_PROGRESS"].includes(detail.status) && now < new Date(detail.endsAt).getTime();
  const cooldownUntil = detail.lastInterimCalculatedAt ? new Date(detail.lastInterimCalculatedAt).getTime() + 300_000 : 0;
  const cooldown = Math.max(0, cooldownUntil - now);
  const assignedCounts = Array.from({ length: Number(teamCount) }, (_, index) =>
    detail.participants.filter((p) => Number(assignments[p.participantId]) === index + 1).length);
  const imbalance = assignedCounts.length && Math.max(...assignedCounts) - Math.min(...assignedCounts) > 1;
  return <>
    <a className="activity-back-link" href={`/kill-competitions.html?communityId=${encodeURIComponent(communityId)}&communityGameId=${encodeURIComponent(new URLSearchParams(location.search).get("communityGameId"))}`}>← 킬내기 목록</a>
    <div className="page-heading kill-detail-heading"><div><p className="eyebrow">{detail.gameMode} Competition</p><h1>{detail.title}</h1>
      <p>생성자 {detail.creator.nickname} · 참가자 {detail.participantCount}명</p></div><span className={`kill-status status-${detail.status.toLowerCase()}`}>{statusLabel(detail.status)}</span></div>
    {message && <p className="message" role="alert">{message}</p>}
    <section className={`panel kill-state-banner status-${detail.status.toLowerCase()}`}><div><strong>{statusLabel(detail.status)}</strong><p>{statusMessage(detail.status)}</p>
      {detail.status === "RESULT_PENDING" && detail.resultLastError && <p>결과 발표 처리 중 오류가 발생했습니다. 잠시 후 다시 시도합니다.</p>}</div>
      {(detail.status === "IN_PROGRESS" || detail.status === "ENDED") && <div className="kill-countdown"><span>남은 시간</span><strong>{remainingLabel(detail.endsAt, now)}</strong></div>}
      {detail.status === "RESULT_PENDING" && <div className="kill-countdown"><span>결과 발표 예정 {formatDateTime(detail.resultPublishAt)}</span><strong>{remainingLabel(detail.resultPublishAt, now)} 남음</strong></div>}</section>
    <section className="panel kill-overview"><dl><div><dt>방식</dt><dd>{detail.gameMode}</dd></div><div><dt>시작</dt><dd>{formatDateTime(detail.startedAt)}</dd></div>
      <div><dt>종료</dt><dd>{formatDateTime(detail.endsAt)}</dd></div><div><dt>참가 신청</dt><dd>{detail.recruitmentOpen ? "ON" : "OFF"}</dd></div></dl>
      {detail.lastInterimCalculatedAt && <p className="kill-api-note">마지막 정산: {formatDateTime(detail.lastInterimCalculatedAt)}{detail.lastInterimMatchStartedAt && <> · 현재 반영된 최근 경기: {formatDateTime(detail.lastInterimMatchStartedAt)} 시작</>}</p>}
      {!detail.scoreEligible && <p className="kill-score-warning">참가자 4명 미만의 킬내기는 랭킹 점수가 지급되지 않습니다.</p>}</section>

    {canChangeRoster && <section className="panel kill-actions-panel"><div><h2>참가 신청 {detail.recruitmentOpen ? "받는 중" : "닫힘"}</h2><p>{detail.status === "IN_PROGRESS" ? "진행 중 신청은 생성자 승인 후 참가가 확정됩니다." : "생성자도 참가하려면 직접 신청해야 합니다."}</p></div>
      <div className="kill-actions">{detail.myParticipantId
        ? pending.some((p) => p.participantId === detail.myParticipantId) && <button className="secondary-button" disabled={busy} onClick={() => mutate("/participants/me", { method: "DELETE" })}>신청 취소</button>
        : detail.pubgNicknameConfigured && detail.recruitmentOpen ? <button disabled={busy} onClick={() => mutate("/participants/me")}>참가 신청</button>
          : <p className="kill-nickname-guide">{detail.recruitmentOpen ? "커뮤니티 닉네임에서 PUBG 인게임 닉네임을 확인할 수 없습니다. 닉네임 형식을 확인해주세요." : "현재 참가 신청을 받지 않습니다."}</p>}
        {detail.creatorView && <button className="secondary-button" disabled={busy} onClick={() => mutate("/recruitment", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ open: !detail.recruitmentOpen }) })}>참가 신청 {detail.recruitmentOpen ? "OFF" : "ON"}</button>}
        {detail.creatorView && detail.status === "RECRUITING" && <button disabled={busy || !detail.participantCount} onClick={() => mutate("/close-recruitment")}>시작 준비</button>}</div></section>}

    {detail.creatorView && canChangeRoster && <ParticipantManager detail={detail} pending={pending} approved={approved}
      members={communityMembers} busy={busy} mutate={mutate} />}

    {detail.status === "READY" && detail.gameMode !== "SOLO" && detail.creatorView && <section className="panel kill-team-builder">
      <div className="kill-section-heading"><div><h2>팀 구성</h2><p>모든 참가자를 한 팀에만 배정해 주세요. 시작 전까지 다시 저장할 수 있습니다.</p></div>
        <label><span>팀 수</span><select value={teamCount} onChange={(e) => setTeamCount(e.target.value)}>
          {Array.from({ length: Math.min(19, Math.max(1, detail.participantCount - 1)) }, (_, i) => i + 2).map((count) => <option key={count}>{count}</option>)}</select></label></div>
      <div className="kill-assignment-list">{detail.participants.map((participant) => <label key={participant.participantId}><span><strong>{participant.nickname}</strong><small>{participant.pubgNickname}</small></span>
        <select value={assignments[participant.participantId] ?? 1} onChange={(e) => setAssignments((old) => ({ ...old, [participant.participantId]: e.target.value }))}>
          {Array.from({ length: Number(teamCount) }, (_, i) => <option value={i + 1} key={i + 1}>TEAM {i + 1}</option>)}</select></label>)}</div>
      {imbalance && <p className="kill-balance-warning">팀별 인원 차이가 2명 이상입니다. 구성을 한 번 더 확인해 주세요.</p>}
      <div className="kill-builder-actions"><button disabled={busy} className="secondary-button" onClick={saveTeams}>팀 구성 저장</button>
        <button disabled={busy || detail.teams.length < 2 || detail.participants.some((p) => !p.teamId)} onClick={() => mutate("/start")}>킬내기 시작</button></div>
    </section>}
    {detail.status === "READY" && detail.gameMode === "SOLO" && detail.creatorView && <section className="panel kill-actions-panel"><div><h2>시작 준비 완료</h2><p>SOLO는 별도 팀 구성 없이 바로 시작할 수 있습니다.</p></div>
      <button disabled={busy} onClick={() => mutate("/start")}>킬내기 시작</button></section>}

    <Roster detail={detail} />

    {canInterim && <section className="panel kill-actions-panel"><div><h2>중간 정산</h2><p>PUBG API 특성상 최근 종료된 경기는 바로 반영되지 않을 수 있습니다. 현재 API에서 확인되는 경기까지만 집계됩니다.</p></div>
      <button disabled={busy || cooldown > 0} onClick={() => mutate("/interim")}>{busy ? "정산 중..." : cooldown > 0 ? `${Math.ceil(cooldown / 60000)}분 후 가능` : "중간 정산"}</button></section>}
    {detail.interimStandings.length > 0 && detail.lastInterimCalculatedAt && <><Standings title="중간 정산 결과" standings={detail.interimStandings} /><p className="kill-api-note">최근 종료된 경기는 PUBG API에 아직 반영되지 않았을 수 있습니다. 현재 결과는 참고용 중간 집계입니다.</p></>}
    {detail.status === "ENDED" && <section className="panel kill-actions-panel"><div><h2>킬내기가 종료되었습니다.</h2><p>최근 종료된 경기는 PUBG API 반영까지 시간이 걸릴 수 있습니다.</p></div>
      {detail.creatorView && <button disabled={busy} onClick={() => mutate("/result-request")}>{busy ? "요청 중..." : "결과 발표 요청"}</button>}</section>}
    {detail.status === "COMPLETED" && <><Standings title="🏆 최종 결과" standings={detail.finalStandings} winners />
      <p className="kill-api-note">결과 집계 완료: {formatDateTime(detail.completedAt)}</p>
      {detail.finalMatches.length > 0 && <MatchEvidence matches={detail.finalMatches} />}</>}
    {detail.administratorView && !["RESULT_PENDING", "COMPLETED", "CANCELLED"].includes(detail.status) && <div className="kill-admin-actions">
      <button className="secondary-button danger-button" disabled={busy} onClick={() => mutate("/cancel")}>관리자 강제 취소</button></div>}
  </>;
}

function ParticipantManager({ detail, pending, approved, members, busy, mutate }) {
  const [memberId, setMemberId] = useState("");
  const [teamId, setTeamId] = useState("");
  const available = members.filter((member) => member.status === "ACTIVE"
    && !detail.participants.some((participant) => participant.memberId === member.id));
  const body = (selectedTeamId) => ({ method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ teamId: selectedTeamId ? Number(selectedTeamId) : null }) });
  return <section className="panel kill-roster"><div className="kill-section-heading"><div><h2>참가자 관리</h2><p>인정 경기가 집계된 참가자는 제외하거나 팀을 변경할 수 없습니다.</p></div></div>
    {pending.length > 0 && <div className="kill-management-list">{pending.map((participant) => <div className="kill-management-row" key={participant.participantId}><span><strong>{participant.nickname}</strong><small>승인 대기</small></span>
      <div className="kill-management-controls">
        {detail.gameMode !== "SOLO" && <select defaultValue="" id={`approve-team-${participant.participantId}`} aria-label={`${participant.nickname} 팀 선택`}><option value="" disabled>팀 선택</option>{detail.teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name}</option>)}</select>}
        <div className="kill-management-actions">
          <button disabled={busy} onClick={() => { const selected = detail.gameMode === "SOLO" ? null : document.getElementById(`approve-team-${participant.participantId}`).value; mutate(`/participants/${participant.participantId}/approve`, body(selected)); }}>승인</button>
          <button className="secondary-button" disabled={busy} onClick={() => mutate(`/participants/${participant.participantId}/reject`)}>거절</button>
        </div>
      </div>
    </div>)}</div>}
    <div className="kill-add-participant">
      <span><strong>클랜원 직접 추가</strong></span>
      <div className="kill-management-controls">
        <select value={memberId} aria-label="직접 추가할 클랜원" onChange={(event) => setMemberId(event.target.value)}><option value="">클랜원 선택</option>{available.map((member) => <option key={member.id} value={member.id}>{member.nickname}</option>)}</select>
        {detail.gameMode !== "SOLO" && detail.status === "IN_PROGRESS" && <select value={teamId} aria-label="추가할 클랜원의 팀" onChange={(event) => setTeamId(event.target.value)}><option value="">팀 선택</option>{detail.teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name}</option>)}</select>}
        <div className="kill-management-actions"><button disabled={busy || !memberId || (detail.gameMode !== "SOLO" && detail.status === "IN_PROGRESS" && !teamId)} onClick={() => mutate("/participants", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ memberId: Number(memberId), teamId: teamId ? Number(teamId) : null }) })}>추가</button></div>
      </div>
    </div>
    {approved.length > 0 && <div className="kill-management-list">{approved.map((participant) => <div className="kill-management-row" key={participant.participantId}><span><strong>{participant.nickname}</strong><small>{participant.eligibleFrom ? `${formatDateTime(participant.eligibleFrom)}부터 인정` : "시작 시각부터 인정"}</small></span>
      <div className="kill-management-controls">
        {detail.gameMode !== "SOLO" && detail.status === "IN_PROGRESS" && <select value={participant.teamId ?? ""} aria-label={`${participant.nickname} 팀`} disabled={busy || participant.interimMatchCount > 0} onChange={(event) => mutate(`/participants/${participant.participantId}/team`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ teamId: Number(event.target.value) }) })}><option value="">팀 미배정</option>{detail.teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name}</option>)}</select>}
        <div className="kill-management-actions"><button className="secondary-button danger-button" disabled={busy || participant.interimMatchCount > 0} onClick={() => mutate(`/participants/${participant.participantId}`, { method: "DELETE" })}>제외</button></div>
      </div>
    </div>)}</div>}
  </section>;
}

function Roster({ detail }) {
  if (!detail.participantCount) return <section className="panel kill-empty compact"><p>아직 참가자가 없습니다.</p></section>;
  if (detail.gameMode === "SOLO" || !detail.teams.length) return <section className="panel kill-roster"><div className="kill-section-heading"><h2>참가자</h2><span>{detail.participantCount}명</span></div>
    <div className="kill-player-grid">{detail.participants.filter((p) => p.participationStatus === "APPROVED").map((p) => <div key={p.participantId}><span><strong>{p.nickname}</strong><small>{p.pubgNickname}{p.eligibleFrom ? ` · ${formatDateTime(p.eligibleFrom)}부터` : ""}</small></span>
      {(detail.status === "IN_PROGRESS" || detail.status === "ENDED") && <b>{p.interimKills}킬</b>}{detail.status === "COMPLETED" && <b>{p.finalKills}킬</b>}</div>)}</div></section>;
  return <section className="kill-team-grid">{detail.teams.map((team) => <article className="panel kill-team-card" key={team.teamId}><header><h2>{team.name}</h2>
    <strong>{detail.status === "COMPLETED" ? `${team.finalKills}킬` : `${team.interimKills}킬`}</strong></header><div>{detail.participants.filter((p) => p.participationStatus === "APPROVED" && p.teamId === team.teamId).map((p) =>
      <p key={p.participantId}><span><b>{p.nickname}</b><small>{p.pubgNickname}</small></span><strong>{detail.status === "COMPLETED" ? p.finalKills : p.interimKills}킬</strong></p>)}</div></article>)}</section>;
}

function Standings({ title, standings, winners = false }) {
  return <section className="panel kill-standings"><div className="kill-section-heading"><h2>{title}</h2>{winners && <span>우승자 +3점 · 일일 한도 적용</span>}</div>
    <ol>{standings.map((row) => <li key={`${row.participantId || "team"}-${row.teamId || row.name}`} className={row.winner ? "is-winner" : ""}>
      <span className="kill-rank">{row.rank}위</span><strong>{row.name}</strong>{row.winner && <em>우승</em>}<b>{row.kills}킬</b></li>)}</ol></section>;
}

function MatchEvidence({ matches }) {
  return <section className="panel kill-match-evidence"><div className="kill-section-heading"><div><h2>최종 반영 경기</h2><p>경기 시작 시각을 기준으로 인정된 결과입니다.</p></div><span>{matches.length}경기</span></div>
    <div>{matches.map((match) => <details key={match.matchId}><summary><span>{formatDateTime(match.startedAt)}</span><code>{match.matchId}</code></summary>
      <ul>{match.players.map((player) => <li key={player.participantId}><span>{player.nickname}</span><strong>{player.kills}킬</strong></li>)}</ul></details>)}</div></section>;
}
