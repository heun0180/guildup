package com.guildup.developer.service;

import com.guildup.developer.dto.DeveloperResponses;
import com.guildup.community.dto.PubgAccountResponse;
import com.guildup.pubg.model.PubgPlatform;
import com.guildup.developer.dto.DeveloperResponses.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DeveloperQueryService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DASHBOARD_RECENT_SIZE = 8;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public DeveloperQueryService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public Dashboard dashboard() {
        return new Dashboard(
                count("select count(*) from communities"),
                count("select count(*) from users"),
                count("select count(*) from community_members"),
                count("select count(*) from discord_community_connections"),
                count("select count(*) from pubg_bingo_events where status in ('ACTIVE', 'SETTLING')"),
                count("select count(*) from pubg_kill_competitions where status in ('IN_PROGRESS', 'RESULT_PENDING')"),
                jdbc.query("select id, name, created_at from communities order by created_at desc, id desc limit ?",
                        (rs, row) -> new RecentCommunity(rs.getLong("id"), rs.getString("name"), instant(rs, "created_at")),
                        DASHBOARD_RECENT_SIZE),
                jdbc.query("""
                        select u.id, u.nickname, a.external_user_id, u.created_at
                        from users u
                        left join user_external_accounts a on a.user_id = u.id and a.provider = 'DISCORD'
                        order by u.created_at desc, u.id desc limit ?
                        """, (rs, row) -> new RecentUser(rs.getLong("id"), rs.getString("nickname"),
                        rs.getString("external_user_id"), instant(rs, "created_at")), DASHBOARD_RECENT_SIZE)
        );
    }

    public DeveloperResponses.Page<CommunitySummary> communities(String query, int requestedPage, int requestedSize) {
        PageSpec page = page(requestedPage, requestedSize);
        SearchClause search = communitySearch(query);
        long total = count("select count(*) from communities c " + search.sql(), search.args());
        List<CommunitySummarySeed> seeds = jdbc.query("""
                        select c.id, c.name, c.created_at,
                               (select cu.user_id from community_users cu where cu.community_id = c.id and cu.role = 'OWNER' order by cu.id fetch first 1 row only) creator_user_id,
                               (select u.nickname from community_users cu join users u on u.id = cu.user_id where cu.community_id = c.id and cu.role = 'OWNER' order by cu.id fetch first 1 row only) creator_nickname,
                               (select count(*) from community_members cm where cm.community_id = c.id) member_count,
                               case when exists (select 1 from discord_community_connections dc where dc.community_id = c.id) then true else false end discord_connected
                        from communities c
                        """ + search.sql() + " order by c.created_at desc, c.id desc limit ? offset ?",
                (rs, row) -> new CommunitySummarySeed(rs.getLong("id"), rs.getString("name"),
                        nullableLong(rs, "creator_user_id"), rs.getString("creator_nickname"),
                        rs.getLong("member_count"), rs.getBoolean("discord_connected"), instant(rs, "created_at")),
                append(search.args(), page.size(), page.offset()));
        Map<Long, List<String>> games = gameTypes(seeds.stream().map(CommunitySummarySeed::id).toList());
        List<CommunitySummary> content = seeds.stream().map(seed -> new CommunitySummary(
                seed.id(), seed.name(), games.getOrDefault(seed.id(), List.of()), seed.creatorUserId(),
                seed.creatorNickname(), seed.memberCount(), seed.discordConnected(), seed.createdAt())).toList();
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public CommunityDetail community(long communityId) {
        try {
            CommunityDetailSeed seed = jdbc.queryForObject("""
                    select c.id, c.name, c.created_at,
                           (select cu.user_id from community_users cu where cu.community_id = c.id and cu.role = 'OWNER' order by cu.id fetch first 1 row only) creator_user_id,
                           (select u.nickname from community_users cu join users u on u.id = cu.user_id where cu.community_id = c.id and cu.role = 'OWNER' order by cu.id fetch first 1 row only) creator_nickname,
                           (select count(*) from community_users cu where cu.community_id = c.id) user_count,
                           (select count(*) from community_members cm where cm.community_id = c.id) member_count,
                           dc.discord_guild_id, dc.discord_guild_name, dc.last_member_synced_at
                    from communities c
                    left join discord_community_connections dc on dc.community_id = c.id
                    where c.id = ?
                    """, (rs, row) -> new CommunityDetailSeed(rs.getLong("id"), rs.getString("name"),
                    nullableLong(rs, "creator_user_id"), rs.getString("creator_nickname"),
                    rs.getLong("user_count"), rs.getLong("member_count"), rs.getString("discord_guild_id"),
                    rs.getString("discord_guild_name"), instant(rs, "last_member_synced_at"),
                    instant(rs, "created_at")), communityId);
            List<Game> games = jdbc.query("select id, game_type from community_games where community_id = ? order by id",
                    (rs, row) -> new Game(rs.getLong("id"), rs.getString("game_type")), communityId);
            return new CommunityDetail(seed.id(), seed.name(), games, seed.creatorUserId(), seed.creatorNickname(),
                    seed.userCount(), seed.memberCount(), seed.discordGuildId() != null, seed.discordGuildId(),
                    seed.discordGuildName(), seed.discordLastMemberSyncedAt(), seed.createdAt());
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("Community");
        }
    }

    public DeveloperResponses.Page<DeveloperResponses.CommunityUser> communityUsers(long communityId, int requestedPage, int requestedSize) {
        requireCommunity(communityId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from community_users where community_id = ?", communityId);
        List<DeveloperResponses.CommunityUser> content = jdbc.query("""
                select u.id user_id, u.nickname, cu.role, cu.joined_at,
                       a.external_user_id discord_user_id, a.external_username discord_username
                from community_users cu
                join users u on u.id = cu.user_id
                left join user_external_accounts a on a.user_id = u.id and a.provider = 'DISCORD'
                where cu.community_id = ?
                order by case cu.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, cu.id
                limit ? offset ?
                """, (rs, row) -> new DeveloperResponses.CommunityUser(rs.getLong("user_id"),
                rs.getString("nickname"), rs.getString("role"), rs.getString("discord_user_id") != null,
                rs.getString("discord_user_id"), rs.getString("discord_username"), instant(rs, "joined_at")),
                communityId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public DeveloperResponses.Page<DeveloperResponses.CommunityMember> communityMembers(long communityId, int requestedPage, int requestedSize) {
        requireCommunity(communityId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from community_members where community_id = ?", communityId);
        List<DeveloperResponses.CommunityMember> content = jdbc.query("""
                select cm.id member_id, cm.nickname, cm.status, cm.created_at, cm.updated_at,
                       discord.external_user_id discord_user_id, discord.external_username discord_username,
                       null pubg_account_id, null pubg_nickname,
                       u.id linked_user_id, u.nickname linked_user_nickname
                from community_members cm
                left join community_member_accounts discord on discord.community_member_id = cm.id and discord.provider = 'DISCORD'
                left join user_external_accounts ua on ua.provider = 'DISCORD' and ua.external_user_id = discord.external_user_id
                left join users u on u.id = ua.user_id
                where cm.community_id = ?
                order by cm.id
                limit ? offset ?
                """, (rs, row) -> new DeveloperResponses.CommunityMember(rs.getLong("member_id"),
                rs.getString("nickname"), rs.getString("status"), rs.getString("discord_user_id"),
                rs.getString("discord_username"), rs.getString("pubg_account_id"), rs.getString("pubg_nickname"),
                nullableLong(rs, "linked_user_id"), rs.getString("linked_user_nickname"),
                instant(rs, "created_at"), instant(rs, "updated_at"), List.of()), communityId, page.size(), page.offset());
        Map<Long, List<PubgAccountResponse>> accounts = pubgAccounts(content.stream().map(DeveloperResponses.CommunityMember::memberId).toList(), communityId);
        content = content.stream().map(member -> member.withPubgAccounts(accounts.getOrDefault(member.memberId(), List.of()))).toList();
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    private Map<Long, List<PubgAccountResponse>> pubgAccounts(List<Long> memberIds, long communityId) {
        if (memberIds.isEmpty()) return Map.of();
        Map<Long, List<PubgAccountResponse>> accounts = new LinkedHashMap<>();
        namedJdbc.query("""
                select community_member_id, platform, external_user_id, external_username
                from community_member_accounts
                where community_id = :communityId and community_member_id in (:memberIds) and provider = 'PUBG'
                order by platform, id
                """, new MapSqlParameterSource("communityId", communityId).addValue("memberIds", memberIds), rs -> {
            accounts.computeIfAbsent(rs.getLong("community_member_id"), ignored -> new ArrayList<>())
                    .add(new PubgAccountResponse(PubgPlatform.valueOf(rs.getString("platform")),
                            rs.getString("external_username"), rs.getString("external_user_id")));
        });
        return accounts;
    }

    public DeveloperResponses.Page<BingoSummary> bingos(long communityId, int requestedPage, int requestedSize) {
        requireCommunity(communityId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_bingo_events where community_id = ?", communityId);
        List<BingoSummary> content = jdbc.query("""
                select b.id, b.title, b.status, b.starts_at, b.ends_at, b.board_size, b.last_aggregated_at,
                       (select count(*) from pubg_bingo_participants p where p.bingo_event_id = b.id) participant_count
                from pubg_bingo_events b where b.community_id = ?
                order by b.starts_at desc, b.id desc limit ? offset ?
                """, (rs, row) -> new BingoSummary(rs.getLong("id"), rs.getString("title"),
                rs.getString("status"), instant(rs, "starts_at"), instant(rs, "ends_at"),
                rs.getInt("board_size"), rs.getLong("participant_count"), instant(rs, "last_aggregated_at")),
                communityId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public BingoDetail bingo(long communityId, long bingoId) {
        requireCommunity(communityId);
        try {
            BingoDetailSeed seed = jdbc.queryForObject("""
                    select b.*, cg.game_type,
                           (select count(*) from pubg_bingo_participants p where p.bingo_event_id = b.id) participant_count,
                           (select count(*) from pubg_bingo_processed_matches pm where pm.bingo_event_id = b.id) processed_match_count
                    from pubg_bingo_events b
                    left join community_games cg on cg.id = b.community_game_id
                    where b.id = ? and b.community_id = ?
                    """, (rs, row) -> new BingoDetailSeed(rs.getLong("id"), rs.getLong("community_id"),
                    nullableLong(rs, "community_game_id"), rs.getString("game_type"), rs.getString("title"),
                    rs.getString("description"), rs.getString("status"), rs.getInt("board_size"),
                    rs.getInt("target_lines"), rs.getBoolean("blackout_enabled"), rs.getBoolean("allow_late_join"),
                    rs.getBoolean("exclude_bot_combat_stats"), rs.getBoolean("clan_play_required"),
                    instant(rs, "starts_at"), instant(rs, "ends_at"), instant(rs, "last_aggregated_at"),
                    instant(rs, "completed_at"), instant(rs, "created_at"), rs.getLong("participant_count"),
                    rs.getLong("processed_match_count")), bingoId, communityId);
            List<BingoMission> missions = jdbc.query("""
                    select id, position, mission_type, aggregation_type, comparison_operator,
                           target_value, occurrence_target, options_json, custom_title
                    from pubg_bingo_cells where bingo_event_id = ? order by position
                    """, (rs, row) -> new BingoMission(rs.getLong("id"), rs.getInt("position"),
                    rs.getString("mission_type"), rs.getString("aggregation_type"),
                    rs.getString("comparison_operator"), rs.getBigDecimal("target_value"),
                    nullableInteger(rs, "occurrence_target"), rs.getString("options_json"),
                    rs.getString("custom_title")), bingoId);
            return seed.withMissions(missions);
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("Bingo");
        }
    }

    public DeveloperResponses.Page<BingoParticipant> bingoParticipants(long communityId, long bingoId,
                                                                        int requestedPage, int requestedSize) {
        requireBingo(communityId, bingoId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_bingo_participants where bingo_event_id = ?", bingoId);
        List<BingoParticipant> content = jdbc.query("""
                select p.id participant_id, u.id user_id, u.nickname user_nickname,
                       p.community_member_id, p.pubg_account_id, p.pubg_nickname,
                       p.line_count, p.joined_at, p.eligible_from, p.last_aggregated_at,
                       (select count(*) from pubg_bingo_progress bp where bp.participant_id = p.id and bp.completed = true) completed_count,
                       (select count(*) from pubg_bingo_progress bp where bp.participant_id = p.id) mission_count
                from pubg_bingo_participants p
                join community_users cu on cu.id = p.community_user_id
                join users u on u.id = cu.user_id
                where p.bingo_event_id = ? order by p.id limit ? offset ?
                """, (rs, row) -> new BingoParticipant(rs.getLong("participant_id"), rs.getLong("user_id"),
                rs.getString("user_nickname"), nullableLong(rs, "community_member_id"),
                rs.getString("pubg_account_id"), rs.getString("pubg_nickname"), rs.getInt("line_count"),
                rs.getLong("completed_count"), rs.getLong("mission_count"), instant(rs, "joined_at"),
                instant(rs, "eligible_from"), instant(rs, "last_aggregated_at")),
                bingoId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public DeveloperResponses.Page<BingoProcessedMatch> bingoProcessedMatches(long communityId, long bingoId,
                                                                               int requestedPage, int requestedSize) {
        requireBingo(communityId, bingoId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_bingo_processed_matches where bingo_event_id = ?", bingoId);
        List<BingoProcessedMatch> content = jdbc.query("""
                select pm.id, pm.participant_id, p.pubg_nickname, p.pubg_account_id,
                       pm.match_id, pm.match_started_at, pm.processed_at
                from pubg_bingo_processed_matches pm
                join pubg_bingo_participants p on p.id = pm.participant_id
                where pm.bingo_event_id = ?
                order by pm.processed_at desc, pm.id desc limit ? offset ?
                """, (rs, row) -> new BingoProcessedMatch(rs.getLong("id"), rs.getLong("participant_id"),
                rs.getString("pubg_nickname"), rs.getString("pubg_account_id"), rs.getString("match_id"),
                instant(rs, "match_started_at"), instant(rs, "processed_at")),
                bingoId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public DeveloperResponses.Page<KillCompetitionSummary> killCompetitions(long communityId,
                                                                             int requestedPage, int requestedSize) {
        requireCommunity(communityId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_kill_competitions where community_id = ?", communityId);
        List<KillCompetitionSummary> content = jdbc.query("""
                select k.id, k.title, k.status, k.game_mode, k.started_at, k.ends_at,
                       k.last_interim_calculated_at, k.completed_at,
                       (select count(*) from pubg_kill_competition_participants p where p.competition_id = k.id) participant_count
                from pubg_kill_competitions k where k.community_id = ?
                order by k.created_at desc, k.id desc limit ? offset ?
                """, (rs, row) -> new KillCompetitionSummary(rs.getLong("id"), rs.getString("title"),
                rs.getString("status"), rs.getString("game_mode"), instant(rs, "started_at"),
                instant(rs, "ends_at"), rs.getLong("participant_count"),
                instant(rs, "last_interim_calculated_at"), instant(rs, "completed_at")),
                communityId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public KillCompetitionDetail killCompetition(long communityId, long competitionId) {
        requireCommunity(communityId);
        try {
            KillDetailSeed seed = jdbc.queryForObject("""
                    select k.*, cg.game_type, cm.nickname created_by_nickname,
                           (select count(*) from pubg_kill_competition_participants p where p.competition_id = k.id) participant_count,
                           (select count(*) from pubg_kill_competition_match_results mr where mr.competition_id = k.id) match_result_count
                    from pubg_kill_competitions k
                    left join community_games cg on cg.id = k.community_game_id
                    join community_members cm on cm.id = k.created_by_member_id
                    where k.id = ? and k.community_id = ?
                    """, (rs, row) -> killDetailSeed(rs), competitionId, communityId);
            List<KillCompetitionTeam> teams = jdbc.query("""
                    select id, team_name, display_order from pubg_kill_competition_teams
                    where competition_id = ? order by display_order, id
                    """, (rs, row) -> new KillCompetitionTeam(rs.getLong("id"), rs.getString("team_name"),
                    rs.getInt("display_order")), competitionId);
            return seed.withTeams(teams);
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("Kill competition");
        }
    }

    public DeveloperResponses.Page<KillCompetitionParticipant> killCompetitionParticipants(
            long communityId, long competitionId, int requestedPage, int requestedSize) {
        requireKillCompetition(communityId, competitionId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_kill_competition_participants where competition_id = ?", competitionId);
        List<KillCompetitionParticipant> content = jdbc.query("""
                select p.id participant_id, p.community_member_id, cm.nickname member_nickname,
                       p.team_id, t.team_name, p.pubg_account_id, p.pubg_nickname,
                       p.participation_status, p.eligible_from, p.interim_kills,
                       p.interim_match_count, p.interim_points, p.final_kills,
                       p.final_match_count, p.final_points
                from pubg_kill_competition_participants p
                join community_members cm on cm.id = p.community_member_id
                left join pubg_kill_competition_teams t on t.id = p.team_id
                where p.competition_id = ? order by p.id limit ? offset ?
                """, (rs, row) -> new KillCompetitionParticipant(rs.getLong("participant_id"),
                rs.getLong("community_member_id"), rs.getString("member_nickname"),
                nullableLong(rs, "team_id"), rs.getString("team_name"), rs.getString("pubg_account_id"),
                rs.getString("pubg_nickname"), rs.getString("participation_status"),
                instant(rs, "eligible_from"), rs.getInt("interim_kills"), rs.getInt("interim_match_count"),
                rs.getInt("interim_points"), nullableInteger(rs, "final_kills"),
                nullableInteger(rs, "final_match_count"), nullableInteger(rs, "final_points")),
                competitionId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public DeveloperResponses.Page<KillCompetitionMatchResult> killCompetitionMatches(
            long communityId, long competitionId, int requestedPage, int requestedSize) {
        requireKillCompetition(communityId, competitionId);
        PageSpec page = page(requestedPage, requestedSize);
        long total = count("select count(*) from pubg_kill_competition_match_results where competition_id = ?", competitionId);
        List<KillCompetitionMatchResult> content = jdbc.query("""
                select mr.id, mr.participant_id, p.pubg_nickname, mr.match_id, mr.match_started_at,
                       mr.kills, mr.placement, mr.kill_points, mr.placement_points, mr.total_points
                from pubg_kill_competition_match_results mr
                join pubg_kill_competition_participants p on p.id = mr.participant_id
                where mr.competition_id = ?
                order by mr.match_started_at desc, mr.id desc limit ? offset ?
                """, (rs, row) -> new KillCompetitionMatchResult(rs.getLong("id"),
                rs.getLong("participant_id"), rs.getString("pubg_nickname"), rs.getString("match_id"),
                instant(rs, "match_started_at"), rs.getInt("kills"), nullableInteger(rs, "placement"),
                rs.getInt("kill_points"), rs.getInt("placement_points"), rs.getInt("total_points")),
                competitionId, page.size(), page.offset());
        return DeveloperResponses.Page.of(content, page.number(), page.size(), total);
    }

    public SearchResponse search(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) return new SearchResponse(List.of());
        int limit = Math.max(1, Math.min(requestedLimit, 20));
        int perTypeLimit = Math.max(1, (limit + 2) / 3);
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        Long numeric = parseLong(query);
        List<SearchResult> results = new ArrayList<>();
        String communitySql = "select c.id, c.name from communities c where lower(c.name) like ?";
        Object[] communityArgs = numeric == null
                ? new Object[]{like, perTypeLimit}
                : new Object[]{like, numeric, perTypeLimit};
        if (numeric != null) communitySql += " or c.id = ?";
        communitySql += " order by c.id desc limit ?";
        results.addAll(jdbc.query(communitySql,
                (rs, row) -> new SearchResult("COMMUNITY", String.valueOf(rs.getLong("id")), null,
                        rs.getString("name"), "Community ID", rs.getLong("id")), communityArgs));
        String userSql = """
                select u.id, u.nickname, a.external_user_id
                from users u left join user_external_accounts a on a.user_id = u.id and a.provider = 'DISCORD'
                where (lower(u.nickname) like ? or a.external_user_id = ?)
                """;
        Object[] userArgs = numeric == null
                ? new Object[]{like, query, perTypeLimit}
                : new Object[]{like, query, numeric, perTypeLimit};
        if (numeric != null) userSql += " or u.id = ?";
        userSql += " order by u.id desc limit ?";
        results.addAll(jdbc.query(userSql, (rs, row) -> new SearchResult("USER", String.valueOf(rs.getLong("id")),
                rs.getString("external_user_id"), rs.getString("nickname"), "GuildUp 사용자", null), userArgs));
        results.addAll(jdbc.query("""
                select a.external_user_id, a.external_username, a.platform, a.community_member_id, a.community_id, cm.nickname
                from community_member_accounts a join community_members cm on cm.id = a.community_member_id
                where a.provider = 'PUBG' and (lower(a.external_username) like ? or a.external_user_id = ?)
                order by a.id desc limit ?
                """, (rs, row) -> new SearchResult("PUBG_ACCOUNT", rs.getString("external_user_id"),
                String.valueOf(rs.getLong("community_member_id")),
                Optional.ofNullable(rs.getString("external_username")).orElse(rs.getString("nickname")),
                "PUBG · " + rs.getString("platform") + " · Community Member ID", rs.getLong("community_id")), like, query, perTypeLimit));
        return new SearchResponse(results.stream().limit(limit).toList());
    }

    private KillDetailSeed killDetailSeed(ResultSet rs) throws SQLException {
        return new KillDetailSeed(rs.getLong("id"), rs.getLong("community_id"),
                nullableLong(rs, "community_game_id"), rs.getString("game_type"), rs.getString("title"),
                rs.getString("status"), rs.getString("game_mode"), rs.getLong("created_by_member_id"),
                rs.getString("created_by_nickname"), rs.getBoolean("recruitment_open"),
                instant(rs, "recruitment_closed_at"), instant(rs, "started_at"), instant(rs, "ends_at"),
                instant(rs, "created_at"), rs.getInt("kill_point"), rs.getBoolean("placement_point_enabled"),
                List.of(rs.getInt("first_place_point"), rs.getInt("second_place_point"),
                        rs.getInt("third_place_point"), placementPoint(rs, "fourth_place_point", "fourth_fifth_place_point"),
                        placementPoint(rs, "fifth_place_point", "fourth_fifth_place_point"),
                        placementPoint(rs, "sixth_place_point", "sixth_tenth_place_point"),
                        placementPoint(rs, "seventh_place_point", "sixth_tenth_place_point"),
                        placementPoint(rs, "eighth_place_point", "sixth_tenth_place_point"),
                        placementPoint(rs, "ninth_place_point", "sixth_tenth_place_point"),
                        placementPoint(rs, "tenth_place_point", "sixth_tenth_place_point")),
                instant(rs, "last_interim_calculated_at"), instant(rs, "last_interim_match_started_at"),
                instant(rs, "interim_calculation_started_at"), instant(rs, "finalization_started_at"),
                Objects.toString(rs.getObject("finalization_claim_token"), null), instant(rs, "result_requested_at"),
                instant(rs, "result_publish_at"), rs.getString("result_last_error"), instant(rs, "completed_at"),
                rs.getLong("participant_count"), rs.getLong("match_result_count"));
    }

    private int placementPoint(ResultSet rs, String exactColumn, String fallbackColumn) throws SQLException {
        Integer exact = nullableInteger(rs, exactColumn);
        return exact == null ? rs.getInt(fallbackColumn) : exact;
    }

    private Map<Long, List<String>> gameTypes(List<Long> communityIds) {
        if (communityIds.isEmpty()) return Map.of();
        Map<Long, List<String>> result = new LinkedHashMap<>();
        List<Map.Entry<Long, String>> rows = namedJdbc.query(
                "select community_id, game_type from community_games where community_id in (:ids) order by id",
                new MapSqlParameterSource("ids", communityIds),
                (rs, row) -> Map.entry(rs.getLong("community_id"), rs.getString("game_type")));
        rows.forEach(row -> result.computeIfAbsent(row.getKey(), ignored -> new ArrayList<>()).add(row.getValue()));
        return result;
    }

    private SearchClause communitySearch(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) return new SearchClause("", new Object[0]);
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        Long id = parseLong(query);
        if (id == null) {
            return new SearchClause("""
                    where lower(c.name) like ? or exists (
                        select 1 from community_users cu join users u on u.id = cu.user_id
                        where cu.community_id = c.id and cu.role = 'OWNER' and lower(u.nickname) like ?)
                    """, new Object[]{like, like});
        }
        return new SearchClause("""
                where c.id = ? or lower(c.name) like ? or exists (
                    select 1 from community_users cu join users u on u.id = cu.user_id
                    where cu.community_id = c.id and cu.role = 'OWNER' and lower(u.nickname) like ?)
                """, new Object[]{id, like, like});
    }

    private void requireCommunity(long communityId) {
        if (count("select count(*) from communities where id = ?", communityId) == 0) throw notFound("Community");
    }
    private void requireBingo(long communityId, long bingoId) {
        if (count("select count(*) from pubg_bingo_events where id = ? and community_id = ?", bingoId, communityId) == 0)
            throw notFound("Bingo");
    }
    private void requireKillCompetition(long communityId, long competitionId) {
        if (count("select count(*) from pubg_kill_competitions where id = ? and community_id = ?", competitionId, communityId) == 0)
            throw notFound("Kill competition");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
    private PageSpec page(int number, int size) {
        int safeNumber = Math.max(0, number);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return new PageSpec(safeNumber, safeSize, safeNumber * safeSize);
    }
    private static Object[] append(Object[] source, Object... tail) {
        Object[] result = Arrays.copyOf(source, source.length + tail.length);
        System.arraycopy(tail, 0, result, source.length, tail.length);
        return result;
    }
    private static Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
    private ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " not found");
    }

    private record PageSpec(int number, int size, int offset) {}
    private record SearchClause(String sql, Object[] args) {}
    private record CommunitySummarySeed(long id, String name, Long creatorUserId, String creatorNickname,
                                        long memberCount, boolean discordConnected, Instant createdAt) {}
    private record CommunityDetailSeed(long id, String name, Long creatorUserId, String creatorNickname,
                                       long userCount, long memberCount, String discordGuildId,
                                       String discordGuildName, Instant discordLastMemberSyncedAt,
                                       Instant createdAt) {}
    private record BingoDetailSeed(long id, long communityId, Long communityGameId, String game,
                                   String title, String description, String status, int boardSize,
                                   int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
                                   boolean excludeBotCombatStats, boolean clanPlayRequired,
                                   Instant startsAt, Instant endsAt, Instant lastAggregatedAt,
                                   Instant completedAt, Instant createdAt, long participantCount,
                                   long processedMatchCount) {
        BingoDetail withMissions(List<BingoMission> missions) {
            return new BingoDetail(id, communityId, communityGameId, game, title, description, status,
                    boardSize, targetLines, blackoutEnabled, allowLateJoin, excludeBotCombatStats,
                    clanPlayRequired, startsAt, endsAt, lastAggregatedAt, completedAt, createdAt,
                    participantCount, processedMatchCount, missions);
        }
    }
    private record KillDetailSeed(long id, long communityId, Long communityGameId, String game,
                                  String title, String status, String gameMode, long createdByMemberId,
                                  String createdByNickname, boolean recruitmentOpen,
                                  Instant recruitmentClosedAt, Instant startedAt, Instant endsAt,
                                  Instant createdAt, int killPoint, boolean placementPointEnabled,
                                  List<Integer> placementPoints, Instant lastInterimCalculatedAt,
                                  Instant lastInterimMatchStartedAt, Instant interimCalculationStartedAt,
                                  Instant finalizationStartedAt, String finalizationClaimToken,
                                  Instant resultRequestedAt, Instant resultPublishAt,
                                  String resultLastError, Instant completedAt, long participantCount,
                                  long matchResultCount) {
        KillCompetitionDetail withTeams(List<KillCompetitionTeam> teams) {
            return new KillCompetitionDetail(id, communityId, communityGameId, game, title, status,
                    gameMode, createdByMemberId, createdByNickname, recruitmentOpen, recruitmentClosedAt,
                    startedAt, endsAt, createdAt, killPoint, placementPointEnabled, placementPoints,
                    lastInterimCalculatedAt, lastInterimMatchStartedAt, interimCalculationStartedAt,
                    finalizationStartedAt, finalizationClaimToken, resultRequestedAt, resultPublishAt,
                    resultLastError, completedAt, participantCount, matchResultCount, teams);
        }
    }
}
