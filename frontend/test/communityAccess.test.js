import test from "node:test";
import assert from "node:assert/strict";
import { canAccessCommunityMenu, canManageCommunity, requiredRolesForLocation } from "../src/communityAccess.js";

test("OWNER와 ADMIN만 GuildUp 커뮤니티를 관리한다", () => {
  assert.equal(canManageCommunity("OWNER"), true);
  assert.equal(canManageCommunity("ADMIN"), true);
  assert.equal(canManageCommunity("MEMBER"), false);
});

test("관리 화면과 Discord 역할 화면은 MEMBER의 직접 URL 접근을 막는다", () => {
  assert.equal(requiredRolesForLocation("/community-settings.html").has("MEMBER"), false);
  for (const path of [
    "/discord-member-role-settings.html",
    "/game-nickname-settings.html",
    "/activity-rule-settings.html",
    "/community-role-settings.html",
  ]) {
    assert.equal(requiredRolesForLocation(path).has("MEMBER"), false);
    assert.equal(requiredRolesForLocation(path).has("ADMIN"), true);
  }
  assert.equal(requiredRolesForLocation("/discord-dm.html").has("ADMIN"), true);
  assert.equal(requiredRolesForLocation("/member-activities.html").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/member-activity.html").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/members.html", "?communityId=1&discordRoles=true").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/members.html", "?communityId=1"), null);
});

test("MEMBER는 일반 메뉴 이동 후에도 관리 메뉴가 활성화되지 않는다", () => {
  for (const role of ["MEMBER", null, undefined]) {
    for (const active of ["news", "members", "rankings", "kill-competitions", "bingos"]) {
      assert.equal(canAccessCommunityMenu(role, active), true);
      assert.equal(canAccessCommunityMenu(role, "activity"), false);
      assert.equal(canAccessCommunityMenu(role, "team-maker"), false);
      assert.equal(canAccessCommunityMenu(role, "integrations"), false);
      assert.equal(canAccessCommunityMenu(role, "settings"), false);
    }
  }
});

test("OWNER와 ADMIN에게만 관리 메뉴를 제공한다", () => {
  for (const role of ["OWNER", "ADMIN"]) {
    for (const menu of ["activity", "team-maker", "integrations", "settings"]) {
      assert.equal(canAccessCommunityMenu(role, menu), true);
    }
  }
});
