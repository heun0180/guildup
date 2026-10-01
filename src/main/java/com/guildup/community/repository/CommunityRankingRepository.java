package com.guildup.community.repository;

import com.guildup.community.domain.RankingPeriodType;
import com.guildup.community.service.RankingPeriod;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class CommunityRankingRepository {
    public record Row(Long memberId, String nickname, int score, int attendanceScore, int killCompetitionScore) {}
    private final NamedParameterJdbcTemplate jdbc;

    public CommunityRankingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Row> findRankings(Long communityId, RankingPeriod period) {
        var parameters = new MapSqlParameterSource("communityId", communityId);
        boolean allTime = period.type() == RankingPeriodType.ALL_TIME;
        String source;
        if (allTime) {
            source = "select community_member_id, score_type, score_change from community_score_history where community_id = :communityId";
        } else {
            parameters.addValue("startDate", period.start()).addValue("endDate", period.end())
                    .addValue("startAt", OffsetDateTime.ofInstant(period.startInstant(), ZoneOffset.UTC))
                    .addValue("endAt", OffsetDateTime.ofInstant(period.endInstant(), ZoneOffset.UTC));
            source = """
                    select h.community_member_id, h.score_type, h.score_change
                    from community_attendances a
                    join community_score_history h on h.reference_id = a.id
                        and h.reference_type = 'ATTENDANCE' and h.score_type = 'ATTENDANCE'
                        and h.community_id = a.community_id and h.community_member_id = a.community_member_id
                    where a.community_id = :communityId
                        and a.attendance_date >= :startDate and a.attendance_date < :endDate
                    union all
                    select h.community_member_id, h.score_type, h.score_change
                    from community_score_history h
                    where h.community_id = :communityId and h.created_at >= :startAt and h.created_at < :endAt
                        and (h.score_type <> 'ATTENDANCE' or not exists (
                            select 1 from community_attendances a
                            where h.reference_type = 'ATTENDANCE' and a.id = h.reference_id
                                and a.community_id = h.community_id and a.community_member_id = h.community_member_id
                        ))
                    """;
        }
        String total = allTime ? "coalesce(s.total_score, 0)" : "coalesce(h.total, 0)";
        String sql = """
                select m.id, m.nickname, %s as score,
                    coalesce(h.attendance, 0) as attendance, coalesce(h.kill_competition, 0) as kill_competition
                from community_members m
                left join community_member_scores s on s.community_member_id = m.id and s.community_id = :communityId
                left join (
                    select community_member_id, sum(score_change) as total,
                        sum(case when score_type = 'ATTENDANCE' then score_change else 0 end) as attendance,
                        sum(case when score_type = 'KILL_COMPETITION_WIN' then score_change else 0 end) as kill_competition
                    from (%s) events group by community_member_id
                ) h on h.community_member_id = m.id
                where m.community_id = :communityId and m.status = 'ACTIVE'
                order by score desc, m.id asc
                """.formatted(total, source);
        return jdbc.query(sql, parameters, (rs, rowNum) -> new Row(
                rs.getLong("id"), rs.getString("nickname"), Math.toIntExact(rs.getLong("score")),
                Math.toIntExact(rs.getLong("attendance")), Math.toIntExact(rs.getLong("kill_competition"))));
    }
}
