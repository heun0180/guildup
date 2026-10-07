package com.guildup.announcement.controller;

import com.guildup.announcement.dto.PlatformAnnouncementResponses.*;
import com.guildup.announcement.service.PlatformAnnouncementService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/announcements")
public class PlatformAnnouncementController {
    private final PlatformAnnouncementService service;
    public PlatformAnnouncementController(PlatformAnnouncementService service) { this.service = service; }

    @GetMapping
    public Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        return service.list(CurrentUserSession.requireUserId(session), page, size);
    }
    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id, HttpSession session) {
        return service.get(CurrentUserSession.requireUserId(session), id);
    }
    @GetMapping("/notifications")
    public Notifications notifications(HttpSession session) {
        return service.notifications(CurrentUserSession.requireUserId(session));
    }
    @GetMapping("/popup")
    public ResponseEntity<Detail> popup(HttpSession session) {
        Detail detail = service.popup(CurrentUserSession.requireUserId(session));
        return detail == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(detail);
    }

    // GET은 읽기 전용이다. 상세 화면이 CSRF 보호된 POST를 호출할 때 읽음이 저장된다.
    @PostMapping("/{id}/read")
    public Detail markRead(@PathVariable Long id, HttpSession session) {
        return service.markRead(CurrentUserSession.requireUserId(session), id, false);
    }
    @PostMapping("/{id}/popup-confirmation")
    public Detail confirmPopup(@PathVariable Long id, HttpSession session) {
        return service.markRead(CurrentUserSession.requireUserId(session), id, true);
    }
}
