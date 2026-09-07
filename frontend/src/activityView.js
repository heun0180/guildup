export const ACTIVITY_STATUS = {
  ACTIVE: { label: "정상", symbol: "✓", tone: "active" },
  NO_CLAN_ACTIVITY: { label: "활동 없음", symbol: "!", tone: "warning" },
  NO_RECENT_MATCHES: { label: "최근 게임 없음", symbol: "!", tone: "muted" },
  ACCOUNT_VERIFICATION_REQUIRED: { label: "계정 확인 필요", symbol: "!", tone: "danger" },
};

export function activityStatus(status) {
  return ACTIVITY_STATUS[status] || { label: "확인 필요", symbol: "!", tone: "muted" };
}

export function formatRelativeDays(value, now = new Date()) {
  if (!value) return "-";
  const playedAt = new Date(value);
  if (Number.isNaN(playedAt.getTime())) return "-";
  const days = Math.max(0, Math.floor((now.getTime() - playedAt.getTime()) / 86_400_000));
  return days === 0 ? "오늘" : `${days}일 전`;
}

export function formatGameMode(mode) {
  const modes = {
    squad: "스쿼드",
    "squad-fpp": "스쿼드 FPP",
    duo: "듀오",
    "duo-fpp": "듀오 FPP",
    solo: "솔로",
    "solo-fpp": "솔로 FPP",
  };
  return modes[mode] || mode || "게임 모드 미확인";
}

export function formatActivityDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: "Asia/Seoul",
  }).format(date);
}

export function activitySyncView(sync, syncing = false) {
  const status = sync?.status || "NEVER_SYNCED";
  const buttonDisabled = syncing || !sync?.syncAvailable;
  if (status === "NEVER_SYNCED") {
    return {
      title: "아직 배틀그라운드 활동을 조회하지 않았습니다.",
      description: null,
      buttonLabel: syncing ? "조회 중..." : "활동 조회",
      buttonDisabled,
    };
  }
  if (status === "SYNCING" || syncing) {
    return {
      title: "배틀그라운드 활동을 조회하고 있습니다.",
      description: "완료될 때까지 잠시 기다려 주세요.",
      buttonLabel: "조회 중...",
      buttonDisabled: true,
    };
  }
  if (status === "FAILED") {
    return {
      title: "마지막 활동 조회에 실패했습니다.",
      description: sync.syncAvailable
        ? "기존 활동 데이터는 그대로 유지되었습니다. 다시 조회할 수 있습니다."
        : "기존 활동 데이터는 유지되었습니다. 잠시 후 다시 시도해 주세요.",
      buttonLabel: syncing ? "조회 중..." : "활동 새로 조회",
      buttonDisabled,
    };
  }
  return {
    title: sync.syncAvailable ? "활동 데이터를 새로 조회할 수 있습니다." : "최신 활동 데이터입니다.",
    description: null,
    buttonLabel: syncing ? "조회 중..." : "활동 새로 조회",
    buttonDisabled,
  };
}
