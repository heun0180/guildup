package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGameActivityRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 커뮤니티에 소유권이 있는 데이터를 FK의 자식부터 명시적으로 제거한다.
 * 모든 쿼리는 같은 community_id를 조건으로 사용하며 공용 PUBG Match Fact 테이블은 건드리지 않는다.
 */
@Repository
public class CommunityDeletionStore {
    private final JdbcTemplate jdbc;

    public CommunityDeletionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 자동 정리에서 생성 시 기본 게임/활동 규칙 외의 콘텐츠·사용자 설정을 보존한다. */
    public boolean hasCommunityContent(Long communityId) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (String table : List.of("community_members", "community_member_accounts",
                "community_member_role_settings", "community_game_nickname_rules",
                "community_notices", "community_events", "community_posts",
                "community_attendances", "community_score_history", "community_member_scores",
                "discord_voice_sessions", "pubg_bingo_events", "pubg_kill_competitions")) {
            conditions.add("exists (select 1 from " + table + " where community_id = ?)");
            parameters.add(communityId);
        }
        for (String table : List.of("community_member_activity_snapshots", "community_game_activity_syncs")) {
            conditions.add("exists (select 1 from " + table + " data join community_games game "
                    + "on game.id = data.community_game_id where game.community_id = ?)");
            parameters.add(communityId);
        }
        conditions.add("(select count(*) from community_games where community_id = ?) > 1");
        parameters.add(communityId);
        conditions.add("exists (select 1 from community_game_activity_rules rule join community_games game "
                + "on game.id = rule.community_game_id where game.community_id = ? "
                + "and (rule.activity_period_days <> ? or rule.minimum_clan_members_in_roster <> ?))");
        parameters.add(communityId);
        parameters.add(CommunityGameActivityRule.DEFAULT_ACTIVITY_PERIOD_DAYS);
        parameters.add(CommunityGameActivityRule.DEFAULT_MINIMUM_CLAN_MEMBERS_IN_ROSTER);
        return Boolean.TRUE.equals(jdbc.queryForObject("select " + String.join(" or ", conditions),
                Boolean.class, parameters.toArray()));
    }

    public void deleteCommunityData(Long communityId) {
        deleteBingoData(communityId);
        deleteKillCompetitionData(communityId);
        deleteBoardData(communityId);
        deleteActivityData(communityId);

        delete("community_attendances", communityId);
        delete("community_score_history", communityId);
        delete("community_member_scores", communityId);
        delete("community_member_accounts", communityId);
        delete("discord_voice_sessions", communityId);
        delete("community_member_role_settings", communityId);
        delete("community_game_nickname_rules", communityId);
        delete("community_notices", communityId);
        delete("community_events", communityId);
        delete("discord_community_connections", communityId);

        jdbc.update("delete from community_game_activity_rules where community_game_id in "
                + "(select id from community_games where community_id = ?)", communityId);
        jdbc.update("delete from community_game_activity_syncs where community_game_id in "
                + "(select id from community_games where community_id = ?)", communityId);

        delete("community_members", communityId);
        delete("community_users", communityId);
        delete("community_games", communityId);
        jdbc.update("delete from communities where id = ?", communityId);
    }

    private void deleteBingoData(Long communityId) {
        String events = "select id from pubg_bingo_events where community_id = ?";
        jdbc.update("delete from pubg_bingo_processed_sources where bingo_event_id in (" + events + ")", communityId);
        jdbc.update("delete from pubg_bingo_processed_matches where bingo_event_id in (" + events + ")", communityId);
        jdbc.update("delete from pubg_bingo_line_completions where participant_id in "
                + "(select id from pubg_bingo_participants where bingo_event_id in (" + events + "))", communityId);
        jdbc.update("delete from pubg_bingo_progress where participant_id in "
                + "(select id from pubg_bingo_participants where bingo_event_id in (" + events + "))", communityId);
        jdbc.update("delete from pubg_bingo_participants where bingo_event_id in (" + events + ")", communityId);
        jdbc.update("delete from pubg_bingo_cells where bingo_event_id in (" + events + ")", communityId);
        jdbc.update("delete from pubg_bingo_events where community_id = ?", communityId);
    }

    private void deleteKillCompetitionData(Long communityId) {
        String competitions = "select id from pubg_kill_competitions where community_id = ?";
        jdbc.update("delete from pubg_kill_competition_match_results where competition_id in ("
                + competitions + ")", communityId);
        jdbc.update("delete from pubg_kill_competition_participants where competition_id in ("
                + competitions + ")", communityId);
        jdbc.update("delete from pubg_kill_competition_teams where competition_id in ("
                + competitions + ")", communityId);
        jdbc.update("delete from pubg_kill_competitions where community_id = ?", communityId);
    }

    private void deleteBoardData(Long communityId) {
        jdbc.update("delete from community_post_comments where post_id in "
                + "(select id from community_posts where community_id = ?)", communityId);
        delete("community_posts", communityId);
    }

    private void deleteActivityData(Long communityId) {
        String snapshots = "select snapshot.id from community_member_activity_snapshots snapshot "
                + "join community_games game on game.id = snapshot.community_game_id "
                + "where game.community_id = ?";
        String matches = "select activity_match.id from community_member_activity_matches activity_match "
                + "where activity_match.activity_snapshot_id in (" + snapshots + ")";
        jdbc.update("delete from community_member_activity_match_players where activity_match_id in ("
                + matches + ")", communityId);
        jdbc.update("delete from community_member_activity_matches where activity_snapshot_id in ("
                + snapshots + ")", communityId);
        jdbc.update("delete from community_member_activity_snapshots where id in (" + snapshots + ")", communityId);
    }

    private void delete(String table, Long communityId) {
        jdbc.update("delete from " + table + " where community_id = ?", communityId);
    }
}
