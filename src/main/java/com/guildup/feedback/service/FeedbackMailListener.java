package com.guildup.feedback.service;

import com.guildup.feedback.exception.FeedbackMailException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 저장이 커밋된 뒤 기존 메일 알림을 보낸다. SMTP 실패가 접수를 취소하지 않는다. */
@Component
public class FeedbackMailListener {
    private final FeedbackMailService mail;
    public FeedbackMailListener(FeedbackMailService mail) { this.mail = mail; }
    @TransactionalEventListener
    public void notifyDeveloper(FeedbackMailMessage message) {
        try { mail.send(message); }
        catch (FeedbackMailException ignored) { /* FeedbackMailService에서 이미 실패를 기록했다. */ }
    }
}
