package com.guildup.feedback.dto;

import com.guildup.feedback.domain.FeedbackStatus;

public record FeedbackManagementRequest(FeedbackStatus status, String answer, Long version) {}
