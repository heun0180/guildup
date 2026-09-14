export const MANAGEMENT_ROLES = new Set(["OWNER", "ADMIN"]);
export const MANAGEMENT_MENU_IDS = new Set(["activity", "team-maker", "integrations", "settings"]);

export function canManageCommunity(role) {
  return MANAGEMENT_ROLES.has(role);
}

/** 메뉴의 active 값이나 화면 이동 상태와 무관하게 MEMBER에게 관리 메뉴를 생성하지 않는다. */
export function canAccessCommunityMenu(role, menuId) {
  return !MANAGEMENT_MENU_IDS.has(menuId) || canManageCommunity(role);
}

export function requiredRolesForLocation(pathname, search = "") {
  const managementPages = new Set([
    "/discord-connect.html",
    "/community-settings.html",
    "/game-nickname-settings.html",
    "/member-activities.html",
    "/member-activity.html",
    "/team-maker.html",
    "/integrations.html",
    "/discord-dm.html",
    "/discord-voice-activity.html",
  ]);
  if (managementPages.has(pathname)) return MANAGEMENT_ROLES;
  if (pathname === "/members.html" && new URLSearchParams(search).get("discordRoles") === "true") {
    return MANAGEMENT_ROLES;
  }
  return null;
}
