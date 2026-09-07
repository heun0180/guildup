package com.guildup.user.auth.dto;

import com.guildup.user.domain.User;

public record LoginUserResponse(
        Long id,
        String nickname
) {

    public static LoginUserResponse from(User user) {
        return new LoginUserResponse(
                user.getId(),
                user.getNickname()
        );
    }
}