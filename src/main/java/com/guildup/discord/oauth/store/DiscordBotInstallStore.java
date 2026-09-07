package com.guildup.discord.oauth.store;

/** 봇 설치 확인용 임시 세션의 생성, 조회, 삭제 방식을 정의한다. */
public interface DiscordBotInstallStore {

    /** 설치 정보를 저장하고 브라우저에 전달할 추측하기 어려운 토큰을 발급한다. */
    String createInstallToken(DiscordBotInstallSession installSession);

    /** 유효한 토큰에 연결된 설치 정보를 조회한다. */
    DiscordBotInstallSession getInstallSession(String installToken);

    /** 설치 확인이 성공한 토큰을 재사용할 수 없도록 제거한다. */
    void removeInstallToken(String installToken);
}
