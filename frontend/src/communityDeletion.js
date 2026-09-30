export function canDeleteCommunity(role) {
  return role === "OWNER";
}

export function matchesCommunityName(input, communityName) {
  return typeof input === "string"
    && typeof communityName === "string"
    && input.trim() === communityName;
}
