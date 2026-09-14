package com.guildup.feedback.controller;

import com.guildup.feedback.dto.FeedbackRequest;
import com.guildup.feedback.dto.FeedbackResponse;
import com.guildup.feedback.service.FeedbackService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/communities/{communityId}/feedback")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public FeedbackResponse send(@PathVariable Long communityId,
                                 @RequestBody FeedbackRequest request,
                                 HttpSession session) {
        return service.send(CurrentUserSession.requireUserId(session), communityId, request);
    }
}
