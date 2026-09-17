const memberCollator = new Intl.Collator("ko-KR", {
  numeric: true,
  sensitivity: "base",
});

export function sortCommunityMembers(members, key, direction = "asc") {
  const valueFor = {
    discordNickname: (member) => member.discordDisplayName?.trim() || member.nickname?.trim() || null,
    discordAccount: (member) => member.discordUsername?.trim() || null,
    gameNickname: (member) => member.gameNickname?.trim() || null,
    discordJoinedAt: (member) => {
      const timestamp = Date.parse(member.discordJoinedAt);
      return Number.isNaN(timestamp) ? null : timestamp;
    },
    status: (member) => member.status?.trim() || null,
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
        ? memberCollator.compare(left.value, right.value)
        : left.value - right.value;
      return comparison === 0 ? left.index - right.index : comparison * multiplier;
    })
    .map(({ member }) => member);
}
