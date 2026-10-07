package com.guildup.user.auth.controller;

import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.auth.service.DiscordAuthAttempt;
import com.guildup.user.auth.service.DiscordConnectionService;
import com.guildup.user.auth.service.WithdrawalVerification;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DiscordConnectionController {
    private final DiscordConnectionService connections;
    public DiscordConnectionController(DiscordConnectionService connections) { this.connections = connections; }

    @DeleteMapping("/api/account/connections/discord")
    public ResponseEntity<Void> disconnect(HttpServletRequest request) {
        var session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            connections.disconnect(CurrentUserSession.requireUserId(session));
            // 진행 중인 OAuth가 방금 해제한 계정을 다시 연결하지 않도록 취소한다.
            session.removeAttribute(DiscordAuthAttempt.ATTRIBUTE);
            session.removeAttribute(WithdrawalVerification.ATTRIBUTE);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
    }
}
