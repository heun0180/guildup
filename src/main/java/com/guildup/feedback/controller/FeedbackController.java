package com.guildup.feedback.controller;

import com.guildup.feedback.dto.*;
import com.guildup.feedback.service.FeedbackService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
public class FeedbackController {
    private final FeedbackService service;
    public FeedbackController(FeedbackService service) { this.service = service; }

    @PostMapping("/api/feedback")
    public FeedbackResponse send(@RequestBody FeedbackRequest request, HttpSession session) {
        return service.send(CurrentUserSession.requireUserId(session), null, request);
    }
    /** 구버전 화면도 공용 접수로 연결한다. communityId는 선택적인 context일 뿐이다. */
    @PostMapping("/api/communities/{communityId}/feedback")
    public FeedbackResponse legacySend(@PathVariable Long communityId, @RequestBody FeedbackRequest request, HttpSession session) {
        return service.send(CurrentUserSession.requireUserId(session), communityId, request);
    }
    @GetMapping("/api/feedback")
    public FeedbackResponses.Page mine(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size, HttpSession session) {
        return service.mine(CurrentUserSession.requireUserId(session), page, size);
    }
    @GetMapping("/api/feedback/{id}")
    public FeedbackResponses.Detail detail(@PathVariable Long id, HttpSession session) {
        return service.mineDetail(CurrentUserSession.requireUserId(session), id);
    }
}
