package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 같은 Discord 서버를 여러 Community에 연결하려는 요청을 거부한다. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DiscordGuildAlreadyConnectedException extends RuntimeException {
    public DiscordGuildAlreadyConnectedException() {
        super("이 Discord 서버는 이미 다른 커뮤니티에 연결되어 있습니다. 기존 커뮤니티를 이용해 주세요. "
                + "내 목록에 없다면 기존 커뮤니티의 사용자 연결을 확인해야 합니다.");
    }
}
