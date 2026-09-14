package com.guildup.feedback.exception;

public class FeedbackMailException extends RuntimeException {
    public FeedbackMailException(Throwable cause) {
        super("Feedback email delivery failed", cause);
    }
}
