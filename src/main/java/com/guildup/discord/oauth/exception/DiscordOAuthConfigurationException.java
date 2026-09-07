package com.guildup.discord.oauth.exception;

/** Discord OAuth 실행에 필요한 환경변수가 누락됐을 때 발생한다. */
public class DiscordOAuthConfigurationException extends RuntimeException {

    public DiscordOAuthConfigurationException(String environmentVariable) {
        super("Discord OAuth 설정이 완료되지 않았습니다. "
                + environmentVariable
                + "를 확인해주세요.");
    }
}
