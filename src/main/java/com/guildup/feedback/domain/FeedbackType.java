package com.guildup.feedback.domain;

public enum FeedbackType {
    FEATURE("기능 건의"),
    BUG("버그 제보"),
    ETC("기타 문의");

    private final String displayName;

    FeedbackType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
