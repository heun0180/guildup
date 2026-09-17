import test from "node:test";
import assert from "node:assert/strict";
import { activityStatus, formatGameMode, formatRelativeDays, sortActivityMembers } from "../src/activityView.js";

test("활동 상태를 운영진이 이해할 수 있는 문구로 표시한다", () => {
  assert.equal(activityStatus("ACTIVE").label, "정상");
  assert.equal(activityStatus("NO_CLAN_ACTIVITY").label, "활동 없음");
  assert.equal(activityStatus("NO_RECENT_MATCHES").label, "최근 게임 없음");
  assert.equal(activityStatus("ACCOUNT_VERIFICATION_REQUIRED").label, "계정 확인 필요");
});

test("최근 활동 시각과 PUBG 게임 모드를 화면용 문구로 바꾼다", () => {
  const now = new Date("2026-09-07T12:00:00Z");
  assert.equal(formatRelativeDays("2026-09-05T11:00:00Z", now), "2일 전");
  assert.equal(formatRelativeDays(null, now), "-");
  assert.equal(formatGameMode("squad"), "스쿼드");
  assert.equal(formatGameMode("squad-fpp"), "스쿼드 FPP");
});

test("24시간이 지나지 않아도 한국 날짜가 어제면 1일 전으로 표시한다", () => {
  const now = new Date("2026-09-07T00:30:00Z");
  const yesterdayInKorea = "2026-09-06T14:59:00Z";

  assert.equal(formatRelativeDays(yesterdayInKorea, now), "1일 전");
});

test("UTC 날짜가 달라도 한국 날짜가 같으면 오늘로 표시한다", () => {
  const now = new Date("2026-09-07T00:30:00Z");
  const earlierTodayInKorea = "2026-09-06T23:30:00Z";

  assert.equal(formatRelativeDays(earlierTodayInKorea, now), "오늘");
});

test("Discord와 인게임 닉네임을 오름차순과 내림차순으로 정렬한다", () => {
  const members = [
    { memberId: 1, discordNickname: "하늘10", gameNickname: "Zulu" },
    { memberId: 2, discordNickname: "하늘2", gameNickname: null },
    { memberId: 3, discordNickname: "구름", gameNickname: "alpha" },
  ];

  assert.deepEqual(
    sortActivityMembers(members, "discordNickname", "asc").map((member) => member.memberId),
    [3, 2, 1],
  );
  assert.deepEqual(
    sortActivityMembers(members, "gameNickname", "desc").map((member) => member.memberId),
    [1, 3, 2],
  );
});

test("최근 활동과 상태를 정렬하고 값이 없는 클랜원은 마지막에 둔다", () => {
  const members = [
    { memberId: 1, lastClanActivityAt: null, status: "ACCOUNT_VERIFICATION_REQUIRED" },
    { memberId: 2, lastClanActivityAt: "2026-09-07T10:00:00Z", status: "ACTIVE" },
    { memberId: 3, lastClanActivityAt: "2026-09-05T10:00:00Z", status: "NO_CLAN_ACTIVITY" },
  ];

  assert.deepEqual(
    sortActivityMembers(members, "lastClanActivityAt", "asc").map((member) => member.memberId),
    [3, 2, 1],
  );
  assert.deepEqual(
    sortActivityMembers(members, "status", "desc").map((member) => member.memberId),
    [1, 3, 2],
  );
});
