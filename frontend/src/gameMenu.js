import { canManageCommunity } from "./communityAccess.js";

const GAME_FEATURES = [
  { capability: "ACTIVITY", id: "activity", label: "인게임 활동", icon: "activity", path: "/member-activities.html", management: true },
  { capability: "TEAM_MAKER", id: "team-maker", label: "팀 만들기", icon: "game", path: "/team-maker.html", management: true },
  { capability: "KILL_COMPETITION", id: "kill-competitions", label: "킬내기", icon: "target", path: "/kill-competitions.html" },
  { capability: "BINGO", id: "bingos", label: "빙고", icon: "bingo", path: "/bingos.html" },
];

export function gameMenuItems(community, communityId) {
  if (!/^\d+$/.test(communityId ?? "")) return [];
  return (community?.games || []).map((game) => ({
    ...game,
    items: GAME_FEATURES.filter((feature) => game.capabilities?.includes(feature.capability))
      .filter((feature) => !feature.management)
      .map((feature) => ({
        ...feature,
        key: `${game.communityGameId}-${feature.id}`,
        href: `${feature.path}?communityId=${encodeURIComponent(communityId)}&communityGameId=${encodeURIComponent(game.communityGameId)}`,
      })),
  })).filter((game) => game.items.length > 0);
}

export function gameManagementMenuItems(community, communityId) {
  if (!canManageCommunity(community?.role) || !/^\d+$/.test(communityId ?? "")) return [];
  const games = community?.games || [];
  const multipleGames = games.length > 1;
  return games.flatMap((game) => GAME_FEATURES
    .filter((feature) => feature.management && game.capabilities?.includes(feature.capability))
    .map((feature) => ({
      ...feature,
      key: `${game.communityGameId}-${feature.id}`,
      label: multipleGames ? `${game.gameName} · ${feature.label}` : feature.label,
      href: `${feature.path}?communityId=${encodeURIComponent(communityId)}&communityGameId=${encodeURIComponent(game.communityGameId)}`,
    })));
}
