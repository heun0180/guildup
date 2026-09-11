package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Discord 서버 멤버 조회, 역할 필터링, 표시 이름 결정을 담당한다. */
@Service
public class DiscordMemberService {

    private static final long MEMBER_SNAPSHOT_TTL_NANOS = Duration.ofSeconds(30).toNanos();

    private final Map<Guild, MemberSnapshot> memberSnapshots = new ConcurrentHashMap<>();
    private final Map<Guild, Object> guildLoadLocks = new ConcurrentHashMap<>();

    /** 기능이 요청한 Discord 서버의 멤버만 온디맨드로 조회하고 짧게 재사용한다. */
    public List<Member> getMembers(Guild guild) {
        long now = System.nanoTime();
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

            List<Member> members = List.copyOf(guild.loadMembers().get());
            memberSnapshots.put(guild, new MemberSnapshot(members, System.nanoTime()));
            return members;
        }
    }

    /** 서버의 전체 멤버 중 봇을 제외한 실제 사용자만 반환한다. */
    public List<Member> getHumanMembers(Guild guild) {
        return getMembers(guild).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
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

}
