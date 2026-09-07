export const ACTIVITY_PERIOD_OPTIONS = [7, 14, 30];
export const MINIMUM_CLAN_MEMBER_OPTIONS = [2, 3, 4];

export function activityRulePayload(activityPeriodDays, minimumClanMembersInRoster) {
  const period = Number(activityPeriodDays);
  const members = Number(minimumClanMembersInRoster);
  if (!Number.isInteger(period) || period < 1 || period > 365) {
    throw new Error("활동 확인 기간은 1일 이상 365일 이하여야 합니다.");
  }
  if (!MINIMUM_CLAN_MEMBER_OPTIONS.includes(members)) {
    throw new Error("활동 인정 인원은 2명, 3명, 4명 중 하나여야 합니다.");
  }
  return { activityPeriodDays: period, minimumClanMembersInRoster: members };
}
