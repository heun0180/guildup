package com.guildup.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Discord 클랜원 동기화에 필요한 역할 설정이 없을 때 발생한다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CommunityMemberRoleSettingRequiredException extends RuntimeException {

    public CommunityMemberRoleSettingRequiredException() {
        super("클랜원으로 인정할 Discord 역할이 설정되지 않았습니다.");
    }
}
