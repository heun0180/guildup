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

/** React StrictMode 재마운트에서도 같은 페이지 진입 요청은 하나의 Promise를 공유한다. */
export function loadVoiceActivityOnce(communityId, loadActivities) {
  if (!activityRequests.has(communityId)) {
    const request = Promise.resolve().then(loadActivities);
    activityRequests.set(communityId, request);
  }
  return activityRequests.get(communityId);
}
const activityRequests = new Map();
