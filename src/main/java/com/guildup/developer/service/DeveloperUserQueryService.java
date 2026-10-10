package com.guildup.developer.service;

import com.guildup.developer.dto.DeveloperResponses;
import com.guildup.developer.dto.DeveloperUserResponses.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DeveloperUserQueryService {
    // Both authentication joins are at most one row by the existing UNIQUE constraints.
    private static final String FROM = """
            from users u
            left join user_credentials e on e.user_id = u.id
            left join user_external_accounts d on d.user_id = u.id and d.provider = 'DISCORD'
            """;
    private static final String SELECT = """
            select u.id, u.nickname, u.status, u.created_at, u.last_login_at, u.last_active_at,
                   e.email, d.external_user_id discord_user_id, d.external_username discord_username
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final Clock clock;

    public DeveloperUserQueryService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, Clock clock) {
        this.jdbc = jdbc; this.namedJdbc = namedJdbc; this.clock = clock;
    }

    public DeveloperResponses.Page<Summary> users(String query, String loginMethod, String sort,
                                                 String direction, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw badRequest("페이지는 0 이상, 크기는 1~100이어야 합니다.");
        String order = switch (sort) {
            case "createdAt" -> "u.created_at";
            case "lastLoginAt" -> "u.last_login_at";
            default -> throw badRequest("지원하지 않는 정렬 기준입니다.");
        };
        String ascending = switch (direction.toLowerCase(Locale.ROOT)) {
            case "asc" -> "asc";
            case "desc" -> "desc";
            default -> throw badRequest("정렬 방향은 asc 또는 desc여야 합니다.");
        };
        List<Object> args = new ArrayList<>();
        String where = search(query, args) + loginFilter(loginMethod);
        Long total = jdbc.queryForObject("select count(*) " + FROM + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        List<Summary> content = jdbc.query(SELECT + FROM + where + " order by " + order + " " + ascending
                        + " nulls last, u.id " + ascending + " limit ? offset ?",
                (rs, row) -> summary(rs), args.toArray());
        return DeveloperResponses.Page.of(withCounts(content), page, size, total == null ? 0 : total);
    }

    public List<Summary> recentUsers() {
        return jdbc.query(SELECT + FROM + " order by u.created_at desc, u.id desc limit 10",
                (rs, row) -> summary(rs));
    }

    public Statistics statistics() {
        Instant now = clock.instant();
        var today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        Instant start = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant week = today.minusDays(6).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        return jdbc.queryForObject("""
                select count(*) total,
                       coalesce(sum(case when created_at >= ? and created_at <= ? then 1 else 0 end), 0) today,
                       coalesce(sum(case when created_at >= ? and created_at <= ? then 1 else 0 end), 0) week,
                       coalesce(sum(case when status = 'ACTIVE' and last_active_at >= ? and last_active_at <= ? then 1 else 0 end), 0) active,
                       coalesce(sum(case when status = 'ACTIVE' then 1 else 0 end), 0) normal
                from users
                """, (rs, row) -> new Statistics(rs.getLong("total"), rs.getLong("today"),
                rs.getLong("week"), rs.getLong("active"), rs.getLong("normal")),
                Timestamp.from(start), Timestamp.from(now), Timestamp.from(week), Timestamp.from(now),
                Timestamp.from(now.minus(30, ChronoUnit.DAYS)), Timestamp.from(now));
    }

    public Detail user(long id) {
        Summary user;
        try {
            user = jdbc.queryForObject(SELECT + FROM + " where u.id = ?", (rs, row) -> summary(rs), id);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        }
        List<LoginAccount> accounts = new ArrayList<>(jdbc.query("""
                select email, created_at from user_credentials where user_id = ?
                """, (rs, row) -> new LoginAccount("EMAIL", rs.getString("email"), null,
                instant(rs, "created_at")), id));
        accounts.addAll(jdbc.query("""
                select provider, external_username, external_user_id, linked_at
                from user_external_accounts where user_id = ? and provider = 'DISCORD' order by id
                """, (rs, row) -> new LoginAccount(rs.getString("provider"), rs.getString("external_username"),
                rs.getString("external_user_id"), instant(rs, "linked_at")), id));
        List<Membership> communities = jdbc.query("""
                select c.id, c.name, cu.role, cu.joined_at, cu.ended_at,
                       coalesce(native.nickname, discord_member.nickname) member_nickname,
                       coalesce(native.status, discord_member.status) member_status
                from community_users cu
                join communities c on c.id = cu.community_id
                left join community_members native on native.id = cu.community_member_id and native.community_id = c.id
                left join user_external_accounts ua on ua.user_id = cu.user_id and ua.provider = 'DISCORD'
                left join community_member_accounts ma on cu.community_member_id is null and ma.community_id = c.id
                    and ma.provider = 'DISCORD' and ma.external_user_id = ua.external_user_id
                left join community_members discord_member on discord_member.id = ma.community_member_id and discord_member.community_id = c.id
                where cu.user_id = ? order by cu.joined_at desc nulls last, c.id desc
                """, (rs, row) -> new Membership(rs.getLong("id"), rs.getString("name"),
                rs.getString("member_nickname"), rs.getString("role"), instant(rs, "joined_at"),
                instant(rs, "ended_at") == null && "ACTIVE".equals(user.status()) ? "ACTIVE" : "ENDED",
                instant(rs, "ended_at"), rs.getString("member_status")), id);
        long count = communities.stream().filter(item -> "ACTIVE".equals(item.status())).count();
        return new Detail(user.withCommunityCount(count), List.copyOf(accounts), communities);
    }

    private List<Summary> withCounts(List<Summary> rows) {
        if (rows.isEmpty()) return rows;
        Map<Long, Long> counts = new HashMap<>();
        namedJdbc.query("""
                select cu.user_id, count(*) total from community_users cu join users u on u.id = cu.user_id
                where cu.user_id in (:ids) and cu.ended_at is null and u.status = 'ACTIVE' group by cu.user_id
                """, Map.of("ids", rows.stream().map(Summary::id).toList()), rs -> {
            counts.put(rs.getLong("user_id"), rs.getLong("total"));
        });
        return rows.stream().map(row -> row.withCommunityCount(counts.getOrDefault(row.id(), 0L))).toList();
    }

    private static String search(String query, List<Object> args) {
        String value = query == null ? "" : query.strip();
        if (value.length() > 254) throw badRequest("검색어는 254자 이하여야 합니다.");
        if (value.isEmpty()) return " where 1 = 1";
        Long id = null;
        try { id = Long.valueOf(value); } catch (NumberFormatException ignored) { /* Text search. */ }
        String like = "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        args.add(id); args.add(like); args.add(like);
        return " where (u.id = ? or lower(u.nickname) like ? escape '!' or lower(e.email) like ? escape '!')";
    }

    private static String loginFilter(String method) {
        return switch (method) {
            case "ALL", "" -> "";
            case "EMAIL" -> " and e.id is not null and d.id is null";
            case "DISCORD" -> " and e.id is null and d.id is not null";
            case "EMAIL_DISCORD" -> " and e.id is not null and d.id is not null";
            case "NONE" -> " and e.id is null and d.id is null";
            default -> throw badRequest("지원하지 않는 로그인 방식입니다.");
        };
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        String email = rs.getString("email"), discord = rs.getString("discord_user_id");
        LoginMethod method = email != null ? (discord != null ? LoginMethod.EMAIL_DISCORD : LoginMethod.EMAIL)
                : (discord != null ? LoginMethod.DISCORD : LoginMethod.NONE);
        return new Summary(rs.getLong("id"), rs.getString("nickname"), method, email, discord,
                rs.getString("discord_username"), 0, instant(rs, "last_login_at"), instant(rs, "last_active_at"),
                instant(rs, "created_at"), rs.getString("status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
