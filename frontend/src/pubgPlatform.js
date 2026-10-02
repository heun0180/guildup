const PLATFORMS = {
  BATTLEGROUNDS_KAKAO: { value: "KAKAO", label: "Kakao" },
  BATTLEGROUNDS_STEAM: { value: "STEAM", label: "Steam" },
};

export const pubgPlatform = (game) => PLATFORMS[game?.gameType] ?? null;
export const pubgGames = (community) => (community?.games ?? []).filter(pubgPlatform);

export function selectedPubgGame(community, gameId) {
  const games = pubgGames(community);
  return gameId ? games.find((game) => String(game.communityGameId) === String(gameId)) ?? null : games[0] ?? null;
}

export function memberForPubgGame(member, game) {
  if (!Array.isArray(member.pubgAccounts)) return member; // Rolling-deployment compatibility.
  const account = member.pubgAccounts.find((item) => item.platform === pubgPlatform(game)?.value);
  return { ...member, gameNickname: account?.nickname ?? null };
}

export function gamePageScopeKey(location) {
  const params = new URLSearchParams(location.search);
  return JSON.stringify([location.pathname, params.get("communityId"), params.get("communityGameId"),
    params.get("discordRoles"), params.get("guildId")]);
}
