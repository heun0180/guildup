package com.guildup.community.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 기존 서비스 검증 패턴에 맞춘 공지/이벤트 입력 검증. */
final class CommunityNewsValidation {
    private CommunityNewsValidation() {}

    static String title(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 1~200자로 입력해 주세요.");
        }
        return value.strip();
    }

    static String content(String value, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > 20000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    required ? "내용은 1~20,000자로 입력해 주세요." : "설명은 20,000자 이하로 입력해 주세요.");
        }
        return value == null ? "" : value.strip();
    }
}
