package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 이미 다른 Discord 서버에 연결된 커뮤니티를 재연결하려 할 때 발생한다. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DiscordCommunityConnectionConflictException extends RuntimeException {

    public DiscordCommunityConnectionConflictException(Long communityId) {
        super("Community is already connected to a different Discord guild: " + communityId);
    }
}
