package com.guildup.feedback.domain;

public enum FeedbackType {
    SERVICE("서비스 문의"),
    FEATURE("기능 건의"),
    BUG("오류 신고"),
    ETC("기타");

    private final String displayName;

    FeedbackType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
