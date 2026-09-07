package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 커뮤니티에 Discord 서버 연결이 설정되지 않았을 때 발생한다. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DiscordCommunityConnectionNotFoundException extends RuntimeException {

    public DiscordCommunityConnectionNotFoundException(Long communityId) {
        super("Discord connection is not configured for Community: " + communityId);
    }
}
