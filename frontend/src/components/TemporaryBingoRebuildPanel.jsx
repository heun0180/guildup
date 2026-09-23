import { useState } from "react";
import { api } from "../api/http.js";

/** TEMPORARY: 2026-09 bingo progress repair tool. Remove after current event verification. */
export default function TemporaryBingoRebuildPanel({ bingoApi, onApplied }) {
  const [running,setRunning]=useState(false), [result,setResult]=useState(null), [error,setError]=useState("");

  async function preview(){
    if(!window.confirm("현재 진행 중인 빙고의 모든 참가자 진행도를 원본 경기 데이터 기준으로 다시 계산합니다.\n\n이 단계에서는 DB를 수정하지 않습니다. 계속하시겠습니까?")) return;
    setRunning(true); setError(""); setResult(null);
    try{setResult(await api(`${bingoApi}/temporary-rebuild/preview`,{method:"POST"}));}
    catch(reason){setError(reason.message);}
    finally{setRunning(false);}
  }
  async function apply(){
    if(!result?.previewToken) return;
    if(!window.confirm("현재 진행 중인 빙고의 모든 참가자 진행도를 미리보기 결과로 교체합니다.\n\n기존 진행도는 재계산된 값으로 교체되며 처리된 경기 원장은 삭제되지 않습니다.\n\n계속하시겠습니까?")) return;
    setRunning(true); setError("");
    try{
      const applied=await api(`${bingoApi}/temporary-rebuild/apply`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({previewToken:result.previewToken})});
      setResult(applied); await onApplied?.();
    }catch(reason){setError(reason.message);}
    finally{setRunning(false);}
  }
  return <section className="panel bingo-rebuild-panel">
    <h2>전체 진행도 재계산 (임시)</h2>
    <p>현재 이벤트의 원본 경기 데이터를 다시 조회하여 16개 미션 진행도를 재계산합니다.</p>
    <button type="button" disabled={running} onClick={preview}>{running?"재계산 중…":"재집계 미리보기"}</button>
    {error&&<p className="message" role="alert">{error}</p>}
    {result&&<div className="bingo-rebuild-result">
      <h3>{result.status==="REBUILD_FAILED"?"재집계 실패":result.status==="REBUILD_APPLIED"?"재집계 적용 완료":"재집계 미리보기 완료"}</h3>
      <p>참가자 {result.participantCount}명 · 재조회 Match {result.matchCount}개 · 변경 진행도 {result.changedProgressCount}개</p>
      {result.failures?.map((failure,index)=><p className="message" key={`${failure.participantId}-${failure.matchId}-${index}`}>{failure.pubgNickname||`참가자 ${failure.participantId}`} · {failure.matchId||"계정"}: {failure.reason}</p>)}
      {result.participants?.map(participant=><details key={participant.participantId}>
        <summary>{participant.pubgNickname||`참가자 ${participant.participantId}`} · 변경 {participant.changes.filter(change=>change.previousValue!==change.recomputedValue||change.previousCompleted!==change.recomputedCompleted).length}개</summary>
        <ul>{participant.changes.map(change=><li key={change.cellId}>{change.title}: {change.previousValue} → {change.recomputedValue}{change.previousCompleted!==change.recomputedCompleted?` (${change.previousCompleted?"완료":"진행 중"} → ${change.recomputedCompleted?"완료":"진행 중"})`:""}</li>)}</ul>
      </details>)}
      {result.status==="REBUILD_PREVIEW_READY"&&<button type="button" disabled={running} onClick={apply}>이 결과 적용</button>}
    </div>}
  </section>;
}
