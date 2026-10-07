export const LOG_GROUPS = [["ALL", "전체"], ["ERROR", "에러"], ["ACTIVITY", "인게임 활동"], ["PUBG_API", "PUBG API"]];
export const SLOW_SYNC_MS = 60_000;
export const EMPTY_FILTERS = { group: "ALL", severity: "", category: "", eventCode: "", communityId: "",
  gameType: "", syncStatus: "", syncId: "", minDurationMs: "", from: "", to: "" };

export function monitoringQuery(filters, page = 0) {
  const params = new URLSearchParams({ page: String(page), size: "20" });
  Object.entries(filters).forEach(([key, raw]) => {
    const value = typeof raw === "string" ? raw.trim() : raw;
    if (value === "" || value == null) return;
    if (key === "from") params.set(key, new Date(`${value}T00:00:00`).toISOString());
    else if (key === "to") params.set(key, new Date(`${value}T23:59:59.999`).toISOString());
    else params.set(key, value);
  });
  return params.toString();
}

export function duration(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? `${(value / 1000).toFixed(1)}s` : "-";
}

export function syncIdOf(event) { return event?.metadata?.syncId || ""; }
export function isSlow(event) { return Number(event?.metadata?.durationMs) >= SLOW_SYNC_MS; }

export function syncSummary(event, latest) {
  const metadata = { ...event?.metadata, ...latest?.metadata };
  return Object.entries({
    syncId: metadata.syncId, communityGameId: metadata.communityGameId, gameType: metadata.gameType,
    "시작 시각": metadata.startedAt, "종료 시각": metadata.endedAt,
    "총 처리시간": metadata.durationMs == null ? undefined : duration(metadata.durationMs),
    "멤버 수": metadata.memberCount, "대상 계정 수": metadata.targetAccounts,
    "확인 플레이어 수": metadata.playersFound, "찾지 못한 플레이어 수": metadata.playersMissing,
    "고유 경기 수": metadata.uniqueMatches, "저장 스냅샷 수": metadata.snapshotCount,
    "현재 / 최종 단계": metadata.stage, "최종 상태": metadata.syncStatus,
    "실패 단계": metadata.failureStage,
    "PUBG HTTP Status": metadata.upstreamStatus, "HTTP Status": metadata.status,
    "오류 종류": metadata.errorType, "PUBG Error Code": metadata.pubgErrorCode,
    "Timeout": metadata.timeout, "429": metadata.rateLimited, "최대 retry 횟수": metadata.retryCount,
    "Player API 호출 수": metadata.playerApiCalls, "Match API 호출 수": metadata.matchApiCalls,
    "Cache hit": metadata.cacheHits, "동시 조회 설정": metadata.fetchConcurrency,
    Exception: metadata.exceptionClass, "원인": metadata.errorMessage,
  }).filter(([, value]) => value !== undefined && value !== null && value !== "");
}
