package com.guildup.discord.oauth.store;

import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;

/** OAuth 요청 검증용 state와 인증 완료 결과를 임시 보관하는 저장소 규약이다. */
public interface DiscordOAuthSessionStore {

    /** 인증을 시작한 커뮤니티에 연결된 일회용 state를 만든다. */
    String createState(Long communityId);

    /** 콜백의 state를 한 번만 사용하고 원래 커뮤니티 ID를 반환한다. */
    Long consumeState(String state);

    /** Discord 사용자와 서버 목록을 저장하고 화면 조회용 결과 ID를 발급한다. */
    String saveResult(Long communityId, DiscordOAuthResultResponse result);

    /** 커뮤니티와 결과 ID가 일치하는 유효한 OAuth 결과를 조회한다. */
    DiscordOAuthResultResponse getResult(Long communityId, String resultId);

    /** 결과를 소비하지 않고 선택 서버의 Discord 관리 권한 증명을 확인한다. */
    DiscordManageableGuildResponse getSelectedGuild(
            Long communityId,
            String resultId,
            String guildId
    );

    /**
     * 선택한 서버가 OAuth 결과에 실제 포함됐는지 확인하고 결과를 일회성으로 소비한다.
     * 클라이언트가 임의의 guildId를 전송해 연결하는 일을 막는다.
     */
    DiscordManageableGuildResponse consumeSelectedGuild(
            Long communityId,
            String resultId,
            String guildId
    );
}
