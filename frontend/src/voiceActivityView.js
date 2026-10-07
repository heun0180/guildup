export const VoiceActivityPeriod = Object.freeze({
  ALL: "ALL", DAY: "DAY", WEEK: "WEEK", MONTH: "MONTH", YEAR: "YEAR",
});

export const voiceActivityPeriods = [
  { value: VoiceActivityPeriod.ALL, label: "전체", description: "전체 누적 음성 활동" },
  { value: VoiceActivityPeriod.DAY, label: "일", description: "오늘 00:00부터 현재까지" },
  { value: VoiceActivityPeriod.WEEK, label: "주", description: "이번 주 월요일 00:00부터 현재까지" },
  { value: VoiceActivityPeriod.MONTH, label: "월", description: "이번 달 1일 00:00부터 현재까지" },
  { value: VoiceActivityPeriod.YEAR, label: "년", description: "올해 1월 1일 00:00부터 현재까지" },
];

export function formatVoiceDuration(totalSeconds) {
  const totalMinutes = Math.floor(Math.max(0, Number(totalSeconds) || 0) / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0 && minutes > 0) return `${hours}시간 ${minutes}분`;
  if (hours > 0) return `${hours}시간`;
  return `${minutes}분`;
}

export function formatVoiceDateTime(value) {
  if (!value) return "활동 없음";
  const parts = new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(value));
  const part = (type) => parts.find((item) => item.type === type)?.value ?? "";
  return `${part("month")}/${part("day")} ${part("hour")}:${part("minute")}`;
}

export function voiceActivityView(members) {
  const rows = Array.isArray(members) ? members : [];
  return {
    rows,
    hasMembers: rows.length > 0,
    hasActivity: rows.some((member) => member.totalSeconds > 0 || member.currentlyConnected),
    hasDiscordAccounts: rows.some((member) => Boolean(member.discordUserId)),
    connectedCount: rows.filter((member) => member.currentlyConnected).length,
  };
}

export async function loadVoiceActivityPageData(loadActivities) {
  try {
    return { status: "loaded", members: await loadActivities() };
  } catch (error) {
    return { status: "error", error };
  }
}

/** 같은 커뮤니티·기간의 진행 중 요청만 공유해 재조회 시 최신 활동을 가져온다. */
export function loadVoiceActivityOnce(communityId, loadActivities, period = VoiceActivityPeriod.ALL, referenceDate = null) {
  const key = JSON.stringify([communityId, period, referenceDate]);
  if (!activityRequests.has(key)) {
    const request = Promise.resolve().then(loadActivities).finally(() => activityRequests.delete(key));
    activityRequests.set(key, request);
  }
  return activityRequests.get(key);
}
const activityRequests = new Map();
