package com.guildup.pubg.support;

import com.guildup.community.domain.GameType;
import com.guildup.community.domain.CommunityGame;
import com.guildup.pubg.model.PubgPlatform;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Keeps PUBG API platform details outside the common game type model. */
public final class PubgGameSupport {
    private PubgGameSupport() {}

    public static String requireShard(GameType gameType) {
        return requirePlatform(gameType).getShard();
    }

    public static PubgPlatform requirePlatform(CommunityGame game) {
        if (game == null) throw unsupported();
        return requirePlatform(game.getGameType());
    }

    public static PubgPlatform requirePlatform(GameType gameType) {
        if (gameType == null) throw unsupported();
        return switch (gameType) {
            case BATTLEGROUNDS_KAKAO -> PubgPlatform.KAKAO;
            case BATTLEGROUNDS_STEAM -> PubgPlatform.STEAM;
        };
    }

    public static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기능은 PUBG 게임에서 지원하지 않습니다.");
    }
}
