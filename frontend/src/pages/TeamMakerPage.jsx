import { useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";

const damage = (value) => value == null ? "-" : new Intl.NumberFormat("ko-KR", {
  maximumFractionDigits: 1,
}).format(value);

export default function TeamMakerPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [participants, setParticipants] = useState([]);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [currentSeason, setCurrentSeason] = useState(true);
  const [previousSeason, setPreviousSeason] = useState(false);
  const [maxMembers, setMaxMembers] = useState(4);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [message, setMessage] = useState("");
  const [result, setResult] = useState(null);
  const [manualDamages, setManualDamages] = useState({});

  useEffect(() => {
    if (!validId) {
      setMessage("주소에 올바른 communityId를 입력해 주세요.");
      setLoading(false);
      return;
    }
    api(`/api/communities/${encodeURIComponent(communityId)}/team-maker/participants`)
      .then((data) => {
        setParticipants(data.participants);
        setSelectedIds(new Set(data.participants.map((participant) => participant.memberId)));
      })
      .catch((error) => {
        if (!redirectToLogin(error)) {
          setMessage(error.status === 403
            ? "팀 만들기는 커뮤니티 운영진과 관리자만 사용할 수 있습니다."
            : error.message || "참가자 목록을 불러오지 못했습니다.");
        }
      })
      .finally(() => setLoading(false));
  }, [communityId, validId]);

  const selectedCount = selectedIds.size;
  const selectedSeasonNames = useMemo(() => [
    currentSeason ? "현재 시즌" : null,
    previousSeason ? "전 시즌" : null,
  ].filter(Boolean).join(" + "), [currentSeason, previousSeason]);

  function toggleParticipant(memberId) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(memberId)) next.delete(memberId);
      else next.add(memberId);
      return next;
    });
    setResult(null);
  }

  function toggleSeason(which) {
    if (which === "current") {
      if (currentSeason && !previousSeason) return;
      setCurrentSeason(!currentSeason);
    } else {
      if (previousSeason && !currentSeason) return;
      setPreviousSeason(!previousSeason);
    }
    setResult(null);
  }

  async function generate() {
    if (selectedCount === 0) {
      setMessage("참가자를 한 명 이상 선택해 주세요.");
      return;
    }
    setGenerating(true);
    setMessage("");
    setResult(null);
    try {
      const generated = await api(`/api/communities/${encodeURIComponent(communityId)}/team-maker/generate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          participantIds: [...selectedIds], currentSeason, previousSeason,
          maxMembersPerTeam: maxMembers, seed: Date.now(),
        }),
      });
      setResult(generated);
      setManualDamages(Object.fromEntries(
        generated.missingStatsParticipants.map((participant) => [participant.memberId, ""]),
      ));
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message || "팀 생성 중 오류가 발생했습니다.");
    } finally {
      setGenerating(false);
    }
  }

  async function regenerate() {
    setGenerating(true);
    setMessage("");
    try {
      const next = await api(`/api/communities/${encodeURIComponent(communityId)}/team-maker/rebalance`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          participants: result.participants,
          maxMembersPerTeam: maxMembers,
          seed: Date.now(),
        }),
      });
      setResult((current) => ({
        ...next,
        currentSeasonId: current.currentSeasonId,
        previousSeasonId: current.previousSeasonId,
        selectedSeasonIds: current.selectedSeasonIds,
      }));
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message || "팀을 다시 만들지 못했습니다.");
    } finally {
      setGenerating(false);
    }
  }

  async function generateWithManualDamages() {
    const missing = result?.missingStatsParticipants ?? [];
    const invalid = missing.find((participant) => {
      const value = manualDamages[participant.memberId];
      return value === "" || value == null || !Number.isFinite(Number(value)) || Number(value) < 0;
    });
    if (invalid) {
      setMessage(`${invalid.discordNickname}님의 평균 딜량을 0 이상의 숫자로 입력해 주세요.`);
      return;
    }

    const manualParticipants = missing.map((participant) => ({
      ...participant,
      damageDealt: Number(manualDamages[participant.memberId]),
      roundsPlayed: 1,
      averageDamage: Number(manualDamages[participant.memberId]),
    }));
    setGenerating(true);
    setMessage("");
    try {
      const next = await api(`/api/communities/${encodeURIComponent(communityId)}/team-maker/rebalance`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          participants: [...result.participants, ...manualParticipants],
          maxMembersPerTeam: maxMembers,
          seed: Date.now(),
        }),
      });
      setResult({
        ...next,
        currentSeasonId: result.currentSeasonId,
        previousSeasonId: result.previousSeasonId,
        selectedSeasonIds: result.selectedSeasonIds,
      });
      setMessage("입력한 평균 딜량을 포함해 팀을 생성했습니다.");
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message || "수동 딜량으로 팀을 만들지 못했습니다.");
    } finally {
      setGenerating(false);
    }
  }

  function excludeMissing() {
    const missingIds = new Set(result.missingStatsParticipants.map((participant) => participant.memberId));
    setSelectedIds((current) => new Set([...current].filter((id) => !missingIds.has(id))));
    setResult(null);
    setMessage("시즌 기록이 없는 참가자를 선택에서 제외했습니다.");
  }

  return (
    <DashboardLayout active="team-maker" communityId={communityId} onError={setMessage}>
      <div className="dashboard-content team-maker-content">
        <div className="page-heading">
          <p className="eyebrow">Team Maker</p>
          <h1>팀 만들기</h1>
          <p>PUBG 시즌 일반 스쿼드 평균 딜량과 인원수를 함께 고려해 참가자를 균형 있게 나눕니다.</p>
        </div>

        <section className="panel team-options-panel" aria-labelledby="team-options-title">
          <div className="team-section-heading">
            <div><h2 id="team-options-title">팀 생성 조건</h2><p>통계에 포함할 시즌과 팀당 최대 인원을 선택하세요.</p></div>
          </div>
          <div className="team-option-grid">
            <fieldset className="team-option-field">
              <legend>시즌 선택</legend>
              <div className="season-checks">
                <label className={`compact-check${currentSeason ? " is-selected" : ""}`}>
                  <input type="checkbox" checked={currentSeason} onChange={() => toggleSeason("current")} />
                  <span className="custom-checkbox"><Icon name="check" size={15} /></span><span>현재 시즌</span>
                </label>
                <label className={`compact-check${previousSeason ? " is-selected" : ""}`}>
                  <input type="checkbox" checked={previousSeason} onChange={() => toggleSeason("previous")} />
                  <span className="custom-checkbox"><Icon name="check" size={15} /></span><span>전 시즌</span>
                </label>
              </div>
              <small>{selectedSeasonNames} 일반 스쿼드의 damageDealt와 roundsPlayed를 합산합니다.</small>
            </fieldset>
            <label className="team-option-field">
              <span>팀당 최대 인원</span>
              <select value={maxMembers} onChange={(event) => { setMaxMembers(Number(event.target.value)); setResult(null); }}>
                {Array.from({ length: 10 }, (_, index) => index + 1).map((value) => (
                  <option value={value} key={value}>{value}명</option>
                ))}
              </select>
              <small>남는 인원도 대기 없이 모든 팀에 최대한 균등하게 배치합니다.</small>
            </label>
          </div>
        </section>

        <section className="panel team-participants-panel" aria-labelledby="team-participants-title">
          <div className="team-participant-toolbar">
            <div><h2 id="team-participants-title">참가자 선택</h2><p>{selectedCount}명 선택 / 총 {participants.length}명</p></div>
            <div className="team-selection-actions">
              <button type="button" className="secondary-button" onClick={() => { setSelectedIds(new Set(participants.map((item) => item.memberId))); setResult(null); }}>전체 선택</button>
              <button type="button" className="secondary-button" onClick={() => { setSelectedIds(new Set()); setResult(null); }}>전체 해제</button>
            </div>
          </div>
          {message && <p className="message team-message" role="alert">{message}</p>}
          {loading && <p className="empty-state">클랜원 목록을 불러오는 중입니다.</p>}
          {!loading && participants.length === 0 && !message && <p className="empty-state">PUBG 인게임 닉네임이 설정된 ACTIVE 클랜원이 없습니다.</p>}
          {!loading && participants.length > 0 && <div className="team-participant-list">
            {participants.map((participant) => {
              const checked = selectedIds.has(participant.memberId);
              return <label className={`team-participant-item${checked ? " is-selected" : ""}`} key={participant.memberId}>
                <input type="checkbox" checked={checked} onChange={() => toggleParticipant(participant.memberId)} />
                <span className="custom-checkbox"><Icon name="check" size={15} /></span>
                <span className="team-participant-name"><strong>{participant.discordNickname}</strong><small>Discord</small></span>
                <span className="team-pubg-name"><strong>{participant.pubgNickname}</strong><small>PUBG</small></span>
              </label>;
            })}
          </div>}
          <div className="team-generate-actions">
            <span>선택한 {selectedCount}명을 최대 {maxMembers}명씩 편성합니다.</span>
            <button type="button" disabled={generating || selectedCount === 0} onClick={generate}>
              <Icon name="game" size={18} />{generating ? "시즌 통계 조회 중..." : "팀 생성"}
            </button>
          </div>
        </section>

        {result?.missingStatsParticipants?.length > 0 && <section className="panel missing-stats-panel" aria-live="polite">
          <div><h2>시즌 기록이 없는 참가자가 있습니다.</h2><p>참가자별 평균 딜량을 직접 입력해 함께 편성하거나, 기록 없는 참가자를 제외할 수 있습니다. 입력값은 저장되지 않습니다.</p></div>
          <div className="manual-damage-list">{result.missingStatsParticipants.map((participant) => (
            <label key={participant.memberId} className="manual-damage-item">
              <span><strong>{participant.discordNickname}</strong><small>{participant.pubgNickname} · 시즌 기록 없음</small></span>
              <span className="manual-damage-input"><input
                type="number"
                min="0"
                step="0.1"
                inputMode="decimal"
                value={manualDamages[participant.memberId] ?? ""}
                onChange={(event) => setManualDamages((current) => ({
                  ...current, [participant.memberId]: event.target.value,
                }))}
                aria-label={`${participant.discordNickname} 평균 딜량`}
                placeholder="예: 320"
              /><small>평균 딜량</small></span>
            </label>
          ))}</div>
          <div className="missing-stats-actions">
            <button type="button" disabled={generating} onClick={generateWithManualDamages}>
              {generating ? "팀 생성 중..." : "입력한 딜량으로 팀 생성"}
            </button>
            <button type="button" className="secondary-button" disabled={generating} onClick={excludeMissing}>기록 없는 참가자 제외</button>
          </div>
        </section>}

        {result?.teams?.length > 0 && <section className="team-results" aria-labelledby="team-results-title">
          <div className="team-results-heading">
            <div><p className="eyebrow">Balance Result</p><h2 id="team-results-title">팀 생성 결과</h2>
              <p>{selectedSeasonNames} · {result.participants.length}명 · {result.teams.length}팀</p></div>
            <button type="button" className="secondary-button" disabled={generating} onClick={regenerate}>
              {generating ? "다시 만드는 중..." : "다시 생성"}
            </button>
          </div>
          <div className="balance-summary-grid">
            <div><span>인원 보정 평균 범위</span><strong>{damage(result.balance.lowestAdjustedAverage)} – {damage(result.balance.highestAdjustedAverage)}</strong><small>빈 자리를 0으로 포함 · 차이 {damage(result.balance.adjustedAverageDifference)}</small></div>
            <div><span>팀 전체 딜량 범위</span><strong>{damage(result.balance.lowestTeamTotal)} – {damage(result.balance.highestTeamTotal)}</strong><small>차이 {damage(result.balance.totalDifference)}</small></div>
          </div>
          <p className="team-balance-note">빈 자리는 딜량 0인 인원으로 계산합니다. 3인 팀과 4인 팀이 함께 있으면 팀 전체 딜량을 맞추기 위해 3인 팀의 실제 평균이 약 33% 높아지도록 편성합니다.</p>
          <div className="team-card-grid">{result.teams.map((team) => (
            <article className="panel balanced-team-card" key={team.teamNumber}>
              <header><div><span>{team.teamNumber}팀</span><strong>{team.memberCount}명 / 실제 평균 {damage(team.averageDamage)}</strong></div><small>보정 평균 {damage(team.adjustedAverageDamage)} · 전체 딜량 {damage(team.totalDamage)}</small></header>
              <div className="balanced-team-members">{team.participants.map((participant) => (
                <div key={participant.memberId}>
                  <span><strong>{participant.discordNickname}</strong><small>{participant.pubgNickname}</small></span>
                  <b>{damage(participant.averageDamage)}</b>
                </div>
              ))}</div>
            </article>
          ))}</div>
        </section>}
      </div>
    </DashboardLayout>
  );
}
