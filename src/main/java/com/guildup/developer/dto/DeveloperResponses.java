package com.guildup.developer.dto;

import com.guildup.community.dto.PubgAccountResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 개발자 조회 API 전용 응답 DTO 모음이다. Entity를 외부에 노출하지 않는다. */
public final class DeveloperResponses {
    private DeveloperResponses() {}

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> Page<T> of(List<T> content, int page, int size, long totalElements) {
            int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
            return new Page<>(content, page, size, totalElements, totalPages);
        }
    }

    public record Dashboard(long communityCount, long userCount, long clanMemberCount,
                            long discordConnectedCommunityCount, long activeBingoCount,
                            long activeKillCompetitionCount, List<RecentCommunity> recentCommunities,
                            List<RecentUser> recentUsers) {}
    public record RecentCommunity(long id, String name, Instant createdAt) {}
    public record RecentUser(long id, String nickname, String discordUserId, Instant createdAt,
                             DeveloperUserResponses.LoginMethod loginMethod, String email, String discordUsername,
                             Instant lastLoginAt, String status) {}

    public record CommunitySummary(long id, String name, List<String> games, Long creatorUserId,
                                   String creatorNickname, long memberCount, boolean discordConnected,
                                   Instant createdAt) {}
    public record CommunityDetail(long id, String name, List<Game> games, Long creatorUserId,
                                  String creatorNickname, long userCount, long memberCount,
                                  boolean discordConnected, String discordGuildId,
                                  String discordGuildName, Instant discordLastMemberSyncedAt,
                                  Instant createdAt) {}
    public record Game(long id, String type) {}
    public record CommunityUser(long userId, String nickname, String communityRole,
                                boolean discordConnected, String discordUserId,
                                String discordUsername, Instant joinedAt) {}
    public record CommunityMember(long memberId, String nickname, String status,
                                  String discordUserId, String discordUsername,
                                  String pubgAccountId, String pubgNickname,
                                  Long linkedUserId, String linkedUserNickname,
                                  Instant createdAt, Instant updatedAt, List<PubgAccountResponse> pubgAccounts) {
        public CommunityMember withPubgAccounts(List<PubgAccountResponse> accounts) {
            return new CommunityMember(memberId, nickname, status, discordUserId, discordUsername,
                    accounts.isEmpty() ? null : accounts.getFirst().accountId(),
                    accounts.isEmpty() ? null : accounts.getFirst().nickname(), linkedUserId, linkedUserNickname,
                    createdAt, updatedAt, List.copyOf(accounts));
        }
    }

    public record BingoSummary(long id, String title, String status, Instant startsAt,
                               Instant endsAt, int boardSize, long participantCount,
                               Instant lastAggregatedAt) {}
    public record BingoDetail(long id, long communityId, Long communityGameId, String game,
                              String title, String description, String status, int boardSize,
                              int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
                              boolean excludeBotCombatStats, boolean clanPlayRequired,
                              Instant startsAt, Instant endsAt, Instant lastAggregatedAt,
                              Instant completedAt, Instant createdAt, long participantCount,
                              long processedMatchCount, List<BingoMission> missions) {}
    public record BingoMission(long id, int position, String missionType, String aggregationType,
                               String operator, BigDecimal targetValue, Integer occurrenceTarget,
                               String optionsJson, String customTitle) {}
    public record BingoParticipant(long participantId, long userId, String userNickname,
                                   Long communityMemberId, String pubgAccountId, String pubgNickname,
                                   int lineCount, long completedMissionCount, long missionCount,
                                   Instant joinedAt, Instant eligibleFrom,
                                   Instant lastAggregatedAt) {}
    public record BingoProcessedMatch(long id, long participantId, String participantNickname,
                                      String pubgAccountId, String matchId,
                                      Instant matchStartedAt, Instant processedAt) {}

    public record KillCompetitionSummary(long id, String title, String status, String gameMode,
                                         Instant startedAt, Instant endsAt, long participantCount,
                                         Instant lastInterimCalculatedAt, Instant completedAt) {}
    public record KillCompetitionDetail(long id, long communityId, Long communityGameId, String game,
                                        String title, String status, String gameMode,
                                        long createdByMemberId, String createdByNickname,
                                        boolean recruitmentOpen, Instant recruitmentClosedAt,
                                        Instant startedAt, Instant endsAt, Instant createdAt,
                                        int killPoint, boolean placementPointEnabled,
                                        List<Integer> placementPoints,
                                        Instant lastInterimCalculatedAt,
                                        Instant lastInterimMatchStartedAt,
                                        Instant interimCalculationStartedAt,
                                        Instant finalizationStartedAt, String finalizationClaimToken,
                                        Instant resultRequestedAt, Instant resultPublishAt,
                                        String resultLastError, Instant completedAt,
                                        long participantCount, long matchResultCount,
                                        List<KillCompetitionTeam> teams) {}
    public record KillCompetitionTeam(long id, String name, int displayOrder) {}
    public record KillCompetitionParticipant(long participantId, long communityMemberId,
                                             String memberNickname, Long teamId, String teamName,
                                             String pubgAccountId, String pubgNickname,
                                             String participationStatus, Instant eligibleFrom,
                                             int interimKills, int interimMatchCount, int interimPoints,
                                             Integer finalKills, Integer finalMatchCount,
                                             Integer finalPoints) {}
    public record KillCompetitionMatchResult(long id, long participantId, String participantNickname,
                                             String matchId, Instant matchStartedAt, int kills,
                                             Integer placement, int killPoints,
                                             int placementPoints, int totalPoints) {}

    public record SearchResponse(List<SearchResult> results) {}
    public record SearchResult(String type, String id, String secondaryId,
                               String label, String description, Long communityId) {}
}
