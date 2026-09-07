package com.guildup.discord.oauth.exception;

import com.guildup.discord.oauth.dto.DiscordOAuthErrorResponse;
import com.guildup.community.exception.DiscordGuildAlreadyConnectedException;
import com.guildup.community.exception.DiscordCommunityConnectionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 별도의 HTTP 상태가 필요한 Discord OAuth 예외를 API 응답 형태로 변환한다. */
@RestControllerAdvice
public class DiscordOAuthExceptionHandler {

    /** 서버 설정 누락은 사용자의 요청 문제가 아니므로 503 Service Unavailable로 반환한다. */
    @ExceptionHandler(DiscordOAuthConfigurationException.class)
    public ResponseEntity<DiscordOAuthErrorResponse> handleConfigurationException(
            DiscordOAuthConfigurationException exception
    ) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new DiscordOAuthErrorResponse(
                status.value(),
                exception.getMessage()
        ));
    }
    @ExceptionHandler(DiscordGuildAlreadyConnectedException.class)
    public ResponseEntity<DiscordOAuthErrorResponse> handleGuildAlreadyConnected(
            DiscordGuildAlreadyConnectedException exception
    ) {
        return conflict(exception.getMessage());
    }

    @ExceptionHandler(DiscordCommunityConnectionConflictException.class)
    public ResponseEntity<DiscordOAuthErrorResponse> handleCommunityAlreadyConnected(
            DiscordCommunityConnectionConflictException exception
    ) {
        return conflict("이 커뮤니티는 이미 다른 Discord 서버에 연결되어 있습니다. 커뮤니티 대시보드에서 연결 상태를 확인해 주세요.");
    }

    @ExceptionHandler(DiscordBotNotInstalledException.class)
    public ResponseEntity<DiscordOAuthErrorResponse> handleBotNotInstalled(DiscordBotNotInstalledException exception) {
        return conflict("아직 GuildUp 봇 설치를 확인할 수 없습니다. Discord 서버에 봇을 추가한 후 설치 확인을 다시 눌러주세요.");
    }

    private ResponseEntity<DiscordOAuthErrorResponse> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DiscordOAuthErrorResponse(HttpStatus.CONFLICT.value(), message));
    }

}
