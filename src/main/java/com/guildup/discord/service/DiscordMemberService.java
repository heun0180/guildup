package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Discord 서버 멤버 조회, 역할 필터링, 표시 이름 결정을 담당한다. */
@Service
public class DiscordMemberService {
    private static final Logger log = LoggerFactory.getLogger(DiscordMemberService.class);

    private static final long MEMBER_SNAPSHOT_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MAX_MEMBER_SNAPSHOTS = 100;

    private final Map<Guild, MemberSnapshot> memberSnapshots = new ConcurrentHashMap<>();
    private final Map<Guild, Object> guildLoadLocks = new ConcurrentHashMap<>();
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    /** 기능이 요청한 Discord 서버의 멤버만 온디맨드로 조회하고 짧게 재사용한다. */
    public List<Member> getMembers(Guild guild) {
        long now = System.nanoTime();
        pruneMemberSnapshots(now);
        MemberSnapshot cached = memberSnapshots.get(guild);
        if (cached != null && cached.isFresh(now)) {
            return cached.members();
        }

        Object loadLock = guildLoadLocks.computeIfAbsent(guild, ignored -> new Object());
        synchronized (loadLock) {
            now = System.nanoTime();
            cached = memberSnapshots.get(guild);
            if (cached != null && cached.isFresh(now)) {
                return cached.members();
            }

            List<Member> members = loadMembers(guild, "MEMBER_SNAPSHOT");
            memberSnapshots.put(guild, new MemberSnapshot(members, System.nanoTime()));
            pruneMemberSnapshots(System.nanoTime());
            return members;
        }
    }

    /** 전체 정합성 확인은 관리 화면의 30초 snapshot을 보관하거나 재사용하지 않고 즉시 해제한다. */
    public List<Member> loadMembersForReconciliation(Guild guild) {
        return loadMembers(guild, "RECONCILIATION_MEMBERS");
    }

    private List<Member> loadMembers(Guild guild, String operation) {
        long started = System.nanoTime();
        try {
            return List.copyOf(guild.loadMembers().get());
        } catch (RuntimeException exception) {
            boolean rateLimit = isRateLimit(exception);
            (rateLimit ? log.atWarn() : log.atError()).setCause(exception).log(
                    "Discord member lookup failed. discordGuildId={}, operation={}, failureType={}, elapsedMs={}",
                    guild.getId(), operation, rateLimit ? "RATE_LIMIT" : exception.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            if (!rateLimit) FailureLogContext.markLogged(exception);
            if (monitoring != null) {
                var metadata = new java.util.LinkedHashMap<String, Object>();
                metadata.put("discordGuildId", guild.getId());
                metadata.put("operation", operation);
                metadata.put("exceptionClass", exception.getClass().getSimpleName());
                if (rateLimit) monitoring.recordWarn(MonitoringCategory.DISCORD, MonitoringEventCode.DISCORD_RATE_LIMIT,
                        "Discord member lookup rate limited", null, null, "discordGuildId=" + guild.getId(), metadata);
                else monitoring.recordError(MonitoringCategory.DISCORD, MonitoringEventCode.DISCORD_MEMBER_LOOKUP_FAILED,
                        "Discord member lookup failed", null, null, "discordGuildId=" + guild.getId(), metadata);
            }
            throw exception;
        }
    }

    private boolean isRateLimit(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof net.dv8tion.jda.api.exceptions.RateLimitedException) return true;
            if (current instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException response
                    && response.getResponse() != null && response.getResponse().code == 429) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    /** 서버의 전체 멤버 중 봇을 제외한 실제 사용자만 반환한다. */
    public List<Member> getHumanMembers(Guild guild) {
        return getMembers(guild).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
    }

    /** Discord 연결을 Community 가입 자격으로 사용할 때 실제 서버 소속 여부를 확인한다. */
    public boolean containsUser(Guild guild, String discordUserId) {
        return getHumanMembers(guild).stream()
                .anyMatch(member -> member.getUser().getId().equals(discordUserId));
    }

    /** 역할 ID를 실제 Role로 찾은 뒤 해당 역할의 멤버를 조회한다. */
    public List<Member> getMembersWithRole(Guild guild, String roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalStateException("Discord role not found: " + roleId);
        }

        return getMembersWithRole(guild, role);
    }

    /** 역할을 가진 멤버 중 봇 계정을 제외한 실제 사용자만 반환한다. */
    public List<Member> getMembersWithRole(Guild guild, Role role) {
        return getMembers(guild).stream()
                .filter(member -> member.getRoles().contains(role))
                .filter(member -> !member.getUser().isBot())
                .toList();
    }

    /** 서버 별명, Discord 전역 표시 이름, 사용자 이름 순으로 표시할 이름을 선택한다. */
    public String getDisplayName(Member member) {
        if (member.getNickname() != null) {
            return member.getNickname();
        }

        if (member.getUser().getGlobalName() != null) {
            return member.getUser().getGlobalName();
        }

        return member.getUser().getName();
    }

    private record MemberSnapshot(List<Member> members, long loadedAtNanos) {
        private boolean isFresh(long now) {
            return now - loadedAtNanos < MEMBER_SNAPSHOT_TTL_NANOS;
        }
    }

    /** 관리 화면 snapshot이 많은 Guild를 순회한 뒤에도 모든 멤버 목록을 계속 참조하지 않게 한다. */
    private void pruneMemberSnapshots(long now) {
        memberSnapshots.entrySet().removeIf(entry -> !entry.getValue().isFresh(now));
        while (memberSnapshots.size() > MAX_MEMBER_SNAPSHOTS) {
            memberSnapshots.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().loadedAtNanos()))
                    .ifPresent(entry -> memberSnapshots.remove(entry.getKey(), entry.getValue()));
        }
    }

}
