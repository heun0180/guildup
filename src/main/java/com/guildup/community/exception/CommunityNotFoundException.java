package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 요청한 커뮤니티가 DB에 없을 때 404 응답을 만들기 위한 예외다. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CommunityNotFoundException extends RuntimeException {

    public CommunityNotFoundException(Long communityId) {
        super("Community not found: " + communityId);
    }
}
