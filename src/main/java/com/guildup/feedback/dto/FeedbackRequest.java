package com.guildup.feedback.dto;

import com.guildup.feedback.domain.FeedbackType;

public record FeedbackRequest(FeedbackType type, String title, String content) {
}
