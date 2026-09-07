import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";

function HighlightedNickname({ discordNickname, gameNickname }) {
  const source = discordNickname || "";
  const target = gameNickname?.trim() || "";
  const index = target ? source.toLocaleLowerCase().indexOf(target.toLocaleLowerCase()) : -1;
  if (index < 0) return <>{source}</>;

  return <>
    {source.slice(0, index)}
    <mark>{source.slice(index, index + target.length)}</mark>
    {source.slice(index + target.length)}
  </>;
}

function PreviewResults({ preview, saved = false, saving = false, onSave }) {
  if (!preview) return null;

  return (
    <section className="nickname-results" aria-labelledby={saved ? "current-results-title" : "preview-results-title"}>
      <div className="nickname-result-heading">
        <div>
          <p className="step-label">{saved ? "현재 적용 결과" : "STEP 3"}</p>
          <h2 id={saved ? "current-results-title" : "preview-results-title"}>
            {saved ? "현재 클랜원 인식 상태" : "규칙을 찾았습니다."}
          </h2>
          <p>{preview.ruleDescription}</p>
        </div>
        <span className="recognition-rate">{preview.successRate}%</span>
      </div>

      {!saved && (
        <div className="nickname-highlight-card">
          <span>분석된 Discord 닉네임</span>
          <strong>
            <HighlightedNickname
              discordNickname={preview.currentDiscordNickname}
              gameNickname={preview.enteredGameNickname}
            />
          </strong>
          <p>인게임 닉네임으로 인식되는 부분 <b>{preview.enteredGameNickname}</b></p>
        </div>
      )}

      <div className="nickname-result-stats" aria-label="닉네임 인식 통계">
        <div><span>전체 클랜원</span><strong>{preview.totalMembers}명</strong></div>
        <div><span>인식 성공</span><strong>{preview.successfulMembers}명</strong></div>
        <div><span>확인 필요</span><strong>{preview.failedMembers}명</strong></div>
        <div><span>성공률</span><strong>{preview.successRate}%</strong></div>
      </div>

      <div className="nickname-preview-table-wrap">
        <table className="member-table nickname-preview-table">
          <thead>
            <tr><th>Discord 닉네임</th><th>인게임 닉네임</th><th>상태</th></tr>
          </thead>
          <tbody>
            {preview.members.map((member) => (
              <tr key={member.discordUserId}>
                <td>
                  <span className="nickname-member">
                    <strong>{member.discordDisplayName}</strong>
                    <small>@{member.discordUsername}</small>
                  </span>
                </td>
                <td className={member.extractedGameNickname ? "" : "secondary-cell"}>
                  {member.extractedGameNickname || "-"}
                </td>
                <td>
                  <span className={`recognition-status ${member.status === "SUCCESS" ? "success" : "warning"}`}>
                    {member.status === "SUCCESS" ? "정상" : "확인 필요"}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {preview.members.length === 0 && <p className="empty-state">미리 볼 Discord 클랜원이 없습니다.</p>}
      </div>

      {!saved && (
        <div className="nickname-save-bar">
          <p>확인이 필요한 클랜원은 규칙 저장 후에도 목록에서 확인할 수 있습니다.</p>
          <button type="button" disabled={saving} onClick={onSave}>
            <Icon name="check" size={17} />{saving ? "저장 중..." : "이 규칙 사용"}
          </button>
        </div>
      )}
    </section>
  );
}

export default function GameNicknameSettingsPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [community, setCommunity] = useState(null);
  const [rule, setRule] = useState(null);
  const [preview, setPreview] = useState(null);
  const [gameNickname, setGameNickname] = useState("");
  const [editing, setEditing] = useState(true);
  const [loading, setLoading] = useState(true);
  const [analyzing, setAnalyzing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [success, setSuccess] = useState("");

  const endpoint = `/api/communities/${encodeURIComponent(communityId || "")}/game-nickname-rule`;

  const showError = useCallback((error, fallback) => {
    if (redirectToLogin(error)) return;
    if (error.status === 403) {
      setMessage("인게임 닉네임 규칙을 설정할 관리 권한이 없습니다.");
      return;
    }
    const detail = error.message || fallback;
    setMessage(detail.includes("찾을 수 없습니다")
      ? `${detail} 닉네임을 다시 확인해 주세요.`
      : detail);
  }, []);

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
        const canManage = dashboard.role === "OWNER" || dashboard.role === "ADMIN";
        if (!canManage) {
          setMessage("인게임 닉네임 규칙을 설정할 관리 권한이 없습니다.");
          return;
        }
        if (!dashboard.discordConnected) return;

        const currentRule = await api(endpoint);
        if (cancelled) return;
        setRule(currentRule);
        setGameNickname(currentRule.sampleGameNickname || "");
        setEditing(!currentRule.configured);
        if (currentRule.configured) {
          const currentPreview = await api(`${endpoint}/preview`);
          if (!cancelled) setPreview(currentPreview);
        }
      } catch (error) {
        if (!cancelled) showError(error, "인게임 닉네임 설정을 불러오지 못했습니다.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, [communityId, endpoint, showError, validId]);

  async function analyzeRule() {
    setAnalyzing(true);
    setMessage("");
    setSuccess("");
    setPreview(null);
    try {
      const result = await api(`${endpoint}/preview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ gameNickname }),
      });
      setPreview(result);
    } catch (error) {
      showError(error, "현재 닉네임 형식에서는 자동 규칙을 만들기 어렵습니다.");
    } finally {
      setAnalyzing(false);
    }
  }

  async function saveRule() {
    setSaving(true);
    setMessage("");
    setSuccess("");
    try {
      const savedRule = await api(endpoint, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ gameNickname }),
      });
      const savedPreview = await api(`${endpoint}/preview`);
      setRule(savedRule);
      setPreview(savedPreview);
      setEditing(false);
      setSuccess("인게임 닉네임 규칙이 저장되었습니다.");
    } catch (error) {
      showError(error, "인게임 닉네임 규칙을 저장하지 못했습니다.");
    } finally {
      setSaving(false);
    }
  }

  function resetRule() {
    setEditing(true);
    setPreview(null);
    setMessage("");
    setSuccess("");
  }

  const canManage = community?.role === "OWNER" || community?.role === "ADMIN";
  const formattedUpdatedAt = rule?.updatedAt
    ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(new Date(rule.updatedAt))
    : null;

  return (
    <DashboardLayout active="game-nickname" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content nickname-settings-content">
        <div className="page-heading">
          <p className="eyebrow">Game nickname</p>
          <h1>인게임 닉네임 설정</h1>
          <p>Discord 서버에서 사용하는 닉네임 형식을 분석하여<br className="desktop-break" /> 클랜원의 인게임 닉네임을 자동으로 구분합니다.</p>
        </div>

        {loading && <section className="panel loading-panel" role="status">설정을 불러오는 중입니다.</section>}
        {message && <p className="message" role="alert">{message}</p>}
        {success && <p className="success-message page-success" role="status">{success}</p>}

        {!loading && community && !community.discordConnected && (
          <section className="panel disconnected-nickname-panel">
            <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
            <div><h2>Discord 서버 연결이 필요합니다.</h2><p>서버를 연결한 뒤 Discord 닉네임 형식을 분석할 수 있습니다.</p></div>
            <a className="button-link" href={`/discord-connect.html?communityId=${encodeURIComponent(communityId)}`}>
              Discord 연결하기 <Icon name="arrow" size={17} />
            </a>
          </section>
        )}

        {!loading && canManage && community?.discordConnected && rule?.configured && !editing && (
          <>
            <section className="panel current-nickname-rule-panel">
              <div>
                <p className="step-label">현재 인게임 닉네임 규칙</p>
                <h2>{preview
                  ? `클랜원 ${preview.totalMembers}명 중 ${preview.successfulMembers}명의 닉네임을 인식하고 있습니다.`
                  : "저장된 닉네임 규칙을 사용하고 있습니다."}</h2>
                <p>{rule.ruleDescription}{formattedUpdatedAt && ` · ${formattedUpdatedAt} 저장`}</p>
              </div>
              <button type="button" className="secondary-button" onClick={resetRule}>규칙 다시 설정</button>
            </section>
            <PreviewResults preview={preview} saved />
          </>
        )}

        {!loading && canManage && community?.discordConnected && rule && editing && (
          <div className="nickname-setup-flow">
            <section className="panel nickname-step-panel">
              <div className="nickname-step-heading">
                <span>1</span><div><p className="step-label">STEP 1</p><h2>내 Discord 닉네임</h2></div>
              </div>
              <div className="readonly-discord-nickname">{rule.currentDiscordNickname}</div>
              <p className="field-help">현재 연결된 Discord 서버에서 사용 중인 닉네임입니다.</p>
            </section>

            <section className="panel nickname-step-panel">
              <div className="nickname-step-heading">
                <span>2</span><div><p className="step-label">STEP 2</p><h2>실제 인게임 닉네임 입력</h2></div>
              </div>
              <label className="game-nickname-field" htmlFor="game-nickname">
                <span>위 닉네임에서 실제 인게임 닉네임을 입력해 주세요.</span>
                <input id="game-nickname" value={gameNickname} autoComplete="off" placeholder="예: sa-gwa"
                       disabled={analyzing || saving}
                       onChange={(event) => {
                         setGameNickname(event.target.value);
                         setPreview(null);
                         setMessage("");
                       }} />
              </label>
              <div className="nickname-find-action">
                <p>입력한 부분을 기준으로 전체 클랜원에게 적용할 수 있는 형식을 찾습니다.</p>
                <button type="button" disabled={analyzing || saving || !gameNickname.trim()} onClick={analyzeRule}>
                  <Icon name="search" size={17} />{analyzing ? "분석 중..." : "닉네임 규칙 찾기"}
                </button>
              </div>
            </section>

            <PreviewResults preview={preview} saving={saving} onSave={saveRule} />
          </div>
        )}
      </div>
    </DashboardLayout>
  );
}
