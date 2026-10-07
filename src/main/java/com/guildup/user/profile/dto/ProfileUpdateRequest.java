package com.guildup.user.profile.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

/** 생략한 필드는 유지하고 birthDate의 명시적인 null은 삭제로 처리한다. */
public class ProfileUpdateRequest {
    private String nickname;
    private String birthDate;
    private boolean nicknameProvided;
    private boolean birthDateProvided;

    @JsonSetter("nickname")
    public void setNickname(String nickname) {
        this.nickname = nickname;
        nicknameProvided = true;
    }

    @JsonSetter("birthDate")
    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
        birthDateProvided = true;
    }

    public String nickname() { return nickname; }
    public String birthDate() { return birthDate; }
    public boolean nicknameProvided() { return nicknameProvided; }
    public boolean birthDateProvided() { return birthDateProvided; }
}
