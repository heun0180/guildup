package com.guildup.developer.dto;

import java.time.Instant;
import java.util.List;

/** 관리자 조회 전용 allowlist. 인증 Entity/비밀번호/토큰은 반환하지 않는다. */
public final class DeveloperUserResponses {
    private DeveloperUserResponses() {}

    public enum LoginMethod { EMAIL, DISCORD, EMAIL_DISCORD, NONE }
    public record Summary(long id, String nickname, LoginMethod loginMethod, String email,
                          String discordUserId, String discordUsername, long communityCount,
                          Instant lastLoginAt, Instant lastActiveAt, Instant createdAt, String status) {
        public Summary withCommunityCount(long count) {
            return new Summary(id, nickname, loginMethod, email, discordUserId, discordUsername, count,
                    lastLoginAt, lastActiveAt, createdAt, status);
        }
    }
    public record Statistics(long totalUsers, long newUsersToday, long newUsersLast7Days,
                             long activeUsersLast30Days, long normalUsers) {}
    public record LoginAccount(String provider, String accountId, String externalUserId, Instant linkedAt) {}
    public record Membership(long communityId, String communityName, String nickname, String role,
                             Instant joinedAt, String status, Instant endedAt, String memberStatus) {}
    public record Detail(Summary user, List<LoginAccount> loginAccounts, List<Membership> communities) {}
}
