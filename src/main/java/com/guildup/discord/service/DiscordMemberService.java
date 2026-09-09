package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Discord 서버 멤버 조회, 역할 필터링, 표시 이름 결정을 담당한다. */
@Service
public class DiscordMemberService {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberService.class);

    /** JDA 캐시에 로드된 서버 전체 멤버를 반환한다. */
    public List<Member> getMembers(Guild guild) {
        return guild.getMembers();
    }

    /** 서버의 전체 멤버 중 봇을 제외한 실제 사용자만 반환한다. */
    public List<Member> getHumanMembers(Guild guild) {
        return getMembers(guild).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
    }

    /** 잘못된 ID를 포함해 서버의 일반 사용자로 확인되지 않으면 empty를 반환한다. */
    public Optional<Member> findHumanMember(Guild guild, String userId) {
        try {
            Member member = guild.getMemberById(userId);
            if (member == null || member.getUser().isBot()) {
                return Optional.empty();
            }
            return Optional.of(member);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
        return guild.getMembersWithRoles(role).stream()
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

    /** 개발 시 멤버 캐시 상태를 확인할 수 있도록 서버 멤버 정보를 로그로 출력한다. */
    public void logMembers(Guild guild) {
        List<Member> members = getMembers(guild);
        log.info("Discord guild member count - guild: {}, count: {}", guild.getName(), members.size());

        for (Member member : members) {
            log.info(
                    "Discord member - userId: {}, username: {}, displayName: {}, bot: {}",
                    member.getUser().getId(),
                    member.getUser().getName(),
                    member.getEffectiveName(),
                    member.getUser().isBot()
            );
        }
    }
}
