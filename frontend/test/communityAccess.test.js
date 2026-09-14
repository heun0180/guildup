import test from "node:test";
import assert from "node:assert/strict";
import { canManageCommunity, requiredRolesForLocation } from "../src/communityAccess.js";

test("OWNER와 ADMIN만 GuildUp 커뮤니티를 관리한다", () => {
  assert.equal(canManageCommunity("OWNER"), true);
  assert.equal(canManageCommunity("ADMIN"), true);
  assert.equal(canManageCommunity("MEMBER"), false);
});

test("관리 화면과 Discord 역할 화면은 MEMBER의 직접 URL 접근을 막는다", () => {
  assert.equal(requiredRolesForLocation("/community-settings.html").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/discord-dm.html").has("ADMIN"), true);
  assert.equal(requiredRolesForLocation("/member-activities.html").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/member-activity.html").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/members.html", "?communityId=1&discordRoles=true").has("MEMBER"), false);
  assert.equal(requiredRolesForLocation("/members.html", "?communityId=1"), null);
});
