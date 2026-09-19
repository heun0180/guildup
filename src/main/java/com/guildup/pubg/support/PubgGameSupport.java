package com.guildup.pubg.support;

import com.guildup.community.domain.GameType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Keeps PUBG API platform details outside the common game type model. */
public final class PubgGameSupport {
    private PubgGameSupport() {}

    public static String requireShard(GameType gameType) {
        return switch (gameType) {
            case BATTLEGROUNDS_KAKAO -> "kakao";
            case BATTLEGROUNDS_STEAM -> "steam";
        };
    }

    public static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기능은 PUBG 게임에서 지원하지 않습니다.");
    }
}
