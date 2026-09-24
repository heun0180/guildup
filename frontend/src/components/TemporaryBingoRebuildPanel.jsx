import { useState } from "react";
import { api } from "../api/http.js";

/** TEMPORARY: 2026-09 bingo progress repair tool. Remove after current event verification. */
export default function TemporaryBingoRebuildPanel({ bingoApi, onApplied }) {
  const [running,setRunning]=useState(false), [result,setResult]=useState(null), [error,setError]=useState("");

  async function preview(){
    if(!window.confirm("현재 진행 중인 빙고의 진행도를 원본 경기 데이터와 비교합니다.\n\n이 단계에서는 DB를 수정하지 않습니다. 계속하시겠습니까?")) return;
    setRunning(true); setError(""); setResult(null);
    try{setResult(await api(`${bingoApi}/temporary-rebuild/preview`,{method:"POST"}));}
    catch(reason){setError(reason.message);}
    finally{setRunning(false);}
  }
  async function apply(){
    if(!result?.previewToken) return;
    if(!window.confirm("검증 결과에서 값이 다른 진행도만 수정합니다.\n\n동일한 진행도와 처리된 경기 원장은 변경하지 않습니다.\n\n계속하시겠습니까?")) return;
    setRunning(true); setError("");
    try{
      const applied=await api(`${bingoApi}/temporary-rebuild/apply`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({previewToken:result.previewToken})});
      setResult(applied); await onApplied?.();
    }catch(reason){setError(reason.message);}
    finally{setRunning(false);}
  }
  return <section className="panel bingo-rebuild-panel">
    <h2>데이터 복구 도구 (임시)</h2>
    <p>현재 이벤트의 16개 미션을 원본 기록과 비교하고 잘못된 진행도만 수정합니다.</p>
    <button type="button" disabled={running} onClick={preview}>{running?"검증 중…":"빙고 데이터 검증"}</button>
    {error&&<p className="message" role="alert">{error}</p>}
    {result&&<div className="bingo-rebuild-result">
      <h3>{result.status==="REBUILD_FAILED"?"검증 실패":result.status==="REBUILD_APPLIED"?"데이터 수정 완료":"검증 완료"}</h3>
      <p>참가자 {result.participantCount}명 · 검증 Match {result.matchCount}개 · 수정 대상 {result.changedProgressCount}개</p>
      {result.failures?.map((failure,index)=><p className="message" key={`${failure.participantId}-${failure.matchId}-${index}`}>{failure.pubgNickname||`참가자 ${failure.participantId}`} · {failure.matchId||"계정"}: {failure.reason}</p>)}
      {result.participants?.map(participant=><details key={participant.participantId}>
        <summary>{participant.pubgNickname||`참가자 ${participant.participantId}`} · {participant.changes.length===0?"변경 없음":`수정 ${participant.changes.length}개`}</summary>
        {participant.changes.length>0&&<ul>{participant.changes.map(change=><li key={change.cellId}>{change.title}: {change.previousValue} → {change.recomputedValue}{change.previousCompleted!==change.recomputedCompleted?` (${change.previousCompleted?"완료":"진행 중"} → ${change.recomputedCompleted?"완료":"진행 중"})`:""}</li>)}</ul>}
      </details>)}
      {result.status==="REBUILD_PREVIEW_READY"&&result.changedProgressCount>0&&<button type="button" disabled={running} onClick={apply}>잘못된 데이터 수정</button>}
    </div>}
  </section>;
}
