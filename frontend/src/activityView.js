export const ACTIVITY_STATUS = {
  ACTIVE: { label: "정상", symbol: "✓", tone: "active" },
  NO_CLAN_ACTIVITY: { label: "활동 없음", symbol: "!", tone: "warning" },
  NO_RECENT_MATCHES: { label: "최근 게임 없음", symbol: "!", tone: "muted" },
  ACCOUNT_VERIFICATION_REQUIRED: { label: "계정 확인 필요", symbol: "!", tone: "danger" },
};

export function activityStatus(status) {
  return ACTIVITY_STATUS[status] || { label: "확인 필요", symbol: "!", tone: "muted" };
}

const ACTIVITY_STATUS_ORDER = {
  ACTIVE: 0,
  NO_CLAN_ACTIVITY: 1,
  NO_RECENT_MATCHES: 2,
  ACCOUNT_VERIFICATION_REQUIRED: 3,
};

const activityMemberCollator = new Intl.Collator("ko-KR", {
  numeric: true,
  sensitivity: "base",
});

export function sortActivityMembers(members, key, direction = "asc") {
  const valueFor = {
    discordNickname: (member) => member.discordNickname?.trim() || null,
    gameNickname: (member) => member.gameNickname?.trim() || null,
    lastClanActivityAt: (member) => {
      const timestamp = Date.parse(member.lastClanActivityAt);
      return Number.isNaN(timestamp) ? null : timestamp;
    },
    status: (member) => ACTIVITY_STATUS_ORDER[member.status] ?? null,
  }[key];
  if (!valueFor) return [...members];

  const multiplier = direction === "desc" ? -1 : 1;
  return members
    .map((member, index) => ({ member, index, value: valueFor(member) }))
    .sort((left, right) => {
      if (left.value == null && right.value == null) return left.index - right.index;
      if (left.value == null) return 1;
      if (right.value == null) return -1;
      const comparison = typeof left.value === "string"
        ? activityMemberCollator.compare(left.value, right.value)
        : left.value - right.value;
      return comparison === 0 ? left.index - right.index : comparison * multiplier;
    })
    .map(({ member }) => member);
}

export function formatRelativeDays(value, now = new Date()) {
  if (!value) return "-";
  const playedAt = new Date(value);
  if (Number.isNaN(playedAt.getTime())) return "-";
  const millisecondsPerDay = 86_400_000;
  const koreaOffset = 9 * 60 * 60 * 1000;
  const playedDate = Math.floor((playedAt.getTime() + koreaOffset) / millisecondsPerDay);
  const currentDate = Math.floor((now.getTime() + koreaOffset) / millisecondsPerDay);
  const days = Math.max(0, currentDate - playedDate);
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

function isSyncAvailable(sync, now) {
  if (sync?.syncAvailable) return true;
  const nextAvailableAt = Date.parse(sync?.nextSyncAvailableAt);
  const currentTime = now instanceof Date ? now.getTime() : Number(now);
  return Number.isFinite(nextAvailableAt)
    && Number.isFinite(currentTime)
    && nextAvailableAt <= currentTime;
}

export function activitySyncView(sync, syncing = false, now = Date.now()) {
  const status = sync?.status || "NEVER_SYNCED";
  const syncAvailable = isSyncAvailable(sync, now);
  const buttonDisabled = syncing || !syncAvailable;
  if (status === "NEVER_SYNCED") {
    return {
      title: "아직 배틀그라운드 활동을 조회하지 않았습니다.",
      description: null,
      buttonLabel: syncing ? "조회 중..." : "활동 조회",
      buttonDisabled,
    };
  }
  if ((status === "SYNCING" && !syncAvailable) || syncing) {
    return {
      title: "배틀그라운드 활동을 조회하고 있습니다.",
      description: "완료될 때까지 잠시 기다려 주세요.",
      buttonLabel: "조회 중...",
      buttonDisabled: true,
    };
  }
  if (status === "SYNCING") {
    return {
      title: "이전 활동 조회가 완료되지 않았습니다.",
      description: "활동 정보를 다시 조회할 수 있습니다.",
      buttonLabel: "활동 다시 조회",
      buttonDisabled,
    };
  }
  if (status === "FAILED") {
    return {
      title: "마지막 활동 조회에 실패했습니다.",
      description: syncAvailable
        ? "기존 활동 데이터는 그대로 유지되었습니다. 다시 조회할 수 있습니다."
        : "기존 활동 데이터는 유지되었습니다. 잠시 후 다시 시도해 주세요.",
      buttonLabel: syncing ? "조회 중..." : "활동 새로 조회",
      buttonDisabled,
    };
  }
  return {
    title: syncAvailable ? "활동 데이터를 새로 조회할 수 있습니다." : "최신 활동 데이터입니다.",
    description: null,
    buttonLabel: syncing ? "조회 중..." : "활동 새로 조회",
    buttonDisabled,
  };
}
