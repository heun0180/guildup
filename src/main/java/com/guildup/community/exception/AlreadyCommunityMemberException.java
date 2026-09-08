package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 이미 존재하는 CommunityUser 관계를 중복 생성하려 할 때 발생한다. */
@ResponseStatus(HttpStatus.CONFLICT)
public class AlreadyCommunityMemberException extends RuntimeException {
    private final Long communityId;

    public AlreadyCommunityMemberException(Long communityId) {
        super("이미 참여 중인 커뮤니티입니다.");
        this.communityId = communityId;
    }

    public Long getCommunityId() {
        return communityId;
    }
}
