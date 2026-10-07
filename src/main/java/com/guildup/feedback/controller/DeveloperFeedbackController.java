package com.guildup.feedback.controller;

import com.guildup.feedback.dto.*;
import com.guildup.feedback.service.FeedbackService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/developer/feedback")
public class DeveloperFeedbackController {
    private final FeedbackService service;
    public DeveloperFeedbackController(FeedbackService service) { this.service = service; }
    @GetMapping
    public FeedbackResponses.Page list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size, HttpSession session) {
        return service.adminList(CurrentUserSession.requireUserId(session), page, size);
    }
    @GetMapping("/{id}")
    public FeedbackResponses.Detail detail(@PathVariable Long id, HttpSession session) {
        return service.adminDetail(CurrentUserSession.requireUserId(session), id);
    }
    @PutMapping("/{id}")
    public FeedbackResponses.Detail manage(@PathVariable Long id, @RequestBody FeedbackManagementRequest request, HttpSession session) {
        return service.manage(CurrentUserSession.requireUserId(session), id, request);
    }
}
