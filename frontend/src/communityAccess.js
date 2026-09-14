export const MANAGEMENT_ROLES = new Set(["OWNER", "ADMIN"]);

export function canManageCommunity(role) {
  return MANAGEMENT_ROLES.has(role);
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
